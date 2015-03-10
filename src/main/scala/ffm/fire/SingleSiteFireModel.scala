package ffm.fire

import scala.Vector

import ffm.ModelSettings._
import ffm.forest.Site
import ffm.forest.Species
import ffm.forest.SpeciesComponent
import ffm.forest.Stratum
import ffm.forest.StratumLevel
import ffm.forest.VegetationWindModel
import ffm.geometry.Coord
import ffm.geometry.CrownPoly
import ffm.geometry.Line
import ffm.geometry.Ray
import ffm.numerics.Numerics


object SingleSiteFireModelRunner {
  
  def run(pathModel: IgnitionPathModel, plantFlameModel: PlantFlameModel)(site: Site, fireLineLength: Double): FireModelResult = {
    val fireModel = new SingleSiteFireModel(pathModel, DefaultPlantFlameModel)(_, _, _)
    val run1 = fireModel(site, true, fireLineLength).run()
    
    val fireSpreadInCanopy = run1.stratumOutcomes exists { outcome =>
      outcome.stratum.level  == StratumLevel.Canopy &&
      outcome.stratumFlameSeries.isDefined
    }
    
    // If there was a canopy stratum with fire spread between crowns, re-run with
    // includeCanopy = false (for altered wind speed calculation)
    val run2 = 
      if (fireSpreadInCanopy)
        fireModel(site, false, fireLineLength).run()
      else
        new FireModelRunResult( new SurfaceParams(site, fireLineLength, false) )
    
    FireModelResult(run1, run2)
  }
}


/**
 * Models fire in a single site.
 */
class SingleSiteFireModel(pathModel: IgnitionPathModel, plantFlameModel: PlantFlameModel)(site: Site, includeCanopy: Boolean, fireLineLength: Double) extends FireModel {

  val surfaceParams = new SurfaceParams(site, fireLineLength, includeCanopy)

  /**
   * Records and tests connections between strata.
   */
  class FlameConnections(connectedLevels: Set[StratumLevel]) {
    /** Creates an empty instance. */
    def this() = this(Set.empty)

    /** Creates a new object with the given stratum set as connected to higher strata. */
    def add(s: Stratum) = new FlameConnections(connectedLevels + s.level)

    /** Tests whether two strata are connected. */
    def isConnected(lower: Stratum, upper: Stratum) =
      connectedLevels.contains(lower.level) || site.vegetation.isVerticalAssociation(lower, upper)
  }

  /**
   * Holds results from a plant or stratum ignition run.
   */
  class IgnitionRunResult(
    val stratum: Stratum,
    val paths: IndexedSeq[IgnitionPath],
    val isIgnition: Boolean,
    val flameAttr: WeightedFlameAttributes) {

    /** Returns paths in which ignition occurred. */
    def pathsWithIgnition = paths filter (_.hasIgnition)
  }

  /**
   * Runs the fire model.
   */
  def run(): FireModelRunResult = {
    val initPreHeatingFlame = PreHeatingFlame(
      surfaceParams.flames.head,
      StratumLevel.Surface,
      startTime = 0,
      endTime = site.surface.flameResidenceTime)

    processStrata(
      site.vegetation.strata,
      Vector(initPreHeatingFlame),
      preHeatingEndTime = -1, // TODO: can we get rid of this magic initial value ?
      new FlameConnections(),
      new FireModelRunResult(surfaceParams))
  }

  /**
   * Models ignition paths and flame series for strata recursively.
   */
  def processStrata(
    strata: IndexedSeq[Stratum],
    preHeatingFlames: IndexedSeq[PreHeatingFlame],
    preHeatingEndTime: Double,
    flameConnections: FlameConnections,
    curData: FireModelRunResult): FireModelRunResult = {

    if (strata.isEmpty) {
      /*
       * Finished processing. 
       * Combine flames from all strata connected to the canopy and return results.
       */
      val allStrataFlames = combinedFlamesForAllStrata(curData.flameSeriess, flameConnections)

      curData withCombinedFlames(allStrataFlames)

    } else {
      /*
       * Process next stratum.
       */
      val stratum = strata.head
      val incidentFlames = createIncidentFlames(stratum, curData.flameSeriess, flameConnections)
      val stratumWindSpeed = VegetationWindModel.windSpeedAtHeight(stratum.averageMidHeight, site, includeCanopy)

      val plantRunContext = IgnitionContext(
        IgnitionRunType.PlantRun,
        site,
        stratum.level,
        preHeatingFlames,
        incidentFlames,
        preHeatingEndTime,
        canopyHeatingDistance = 0.0,
        stratumWindSpeed)

      val plantRunResult = findPlantIgnitionPaths(stratum, plantRunContext)

      if (!plantRunResult.isIgnition) {
        /*
         * Nothing more to do for this stratum.
         * Add the paths (which will have pre-ignition data) to the accumulator
         * and process the remaining strata. 
         */
        
        val outcome = StratumOutcome.nonIgnitionOutcome(stratum, plantRunResult.paths)
        
        processStrata(
          strata.tail,
          preHeatingFlames,
          preHeatingEndTime,
          flameConnections,
          curData.withStratumOutcome(outcome))

      } else {
        val plantFlames = getPlantFlames(plantRunResult, stratumWindSpeed)        
        
        val canopyHeatingDistance =
          if (stratum.level == StratumLevel.Canopy) calculateCanopyHeatingDistance(stratum, curData.flameSeriess)
          else 0.0

        val stratumRunContext = plantRunContext.copy(
          runType = IgnitionRunType.StratumRun,
          preHeatingFlames = Vector.empty,
          preHeatingEndTime = 0.0,
          incidentFlames = plantFlames,
          canopyHeatingDistance = canopyHeatingDistance)

        val stratumRunResult = findStratumIgnitionPaths(stratum, stratumRunContext, plantFlames.head)

        val stratumFlames = getStratumFlames(stratumRunResult, stratumWindSpeed)

        val outcome = StratumOutcome.ignitionOutcome(
          stratum,
          plantRunResult.paths,
          plantFlames,
          stratumRunResult.paths,
          stratumFlames
        )

        /*
         * TODO: logic for connection based on comparing max plant flame length to max stratum flame length.
         * 
         * In runs of the C++ model this never seems to be used. 
         * 
         * Ask Phil about this.
         */

        // guard against magic -1 value for preHeatingEndTime (TODO - get rid of that)
        val preHeatingStartTime = 
          (preHeatingEndTime max 0.0) + 
          plantRunResult.flameAttr.ignitionTime + 
          plantRunResult.flameAttr.timeToLongestFlame

        // If we are here, it should be safe to call `get` on the largestFlameSeries Option.
        val flameSeries = outcome.largestFlameSeries.get  
          
        val nextPHFlame = createPreHeatingFlame(
          flameSeries, 
          startTime = preHeatingStartTime, 
          windSpeed = stratumWindSpeed)

        val nextConnections =
          if (plantRunResult.pathsWithIgnition exists (path => isFlameBeyondCrown(path, stratumWindSpeed)))
            flameConnections add stratum
          else
            flameConnections

        processStrata(
          strata.tail,
          preHeatingFlames :+ nextPHFlame,
          preHeatingEndTime = preHeatingStartTime,
          nextConnections,
          curData.withStratumOutcome(outcome))
      }
    }
  }

  /** Finds maximum length of a flame in the given collection (0 if empty). */
  private def maxFlameLength(flames: IndexedSeq[Flame]) =
    flames.foldLeft(0.0)((len, flame) => len max flame.flameLength)

  /** Creates a new pre-heating flame based on the given flame series. */
  private def createPreHeatingFlame(flameSeries: StratumFlameSeries, startTime: Double, windSpeed: Double) = {
    val angle = Flame.windEffectFlameAngle(flameSeries.meanFlameLength, windSpeed, site.slope)
    val flame = Flame(flameSeries.meanFlameLength, angle, flameSeries.meanOrigin, flameSeries.meanDepthIgnited, flameSeries.meanDeltaTemperature)
    val endTime = startTime + flameSeries.size * ComputationTimeInterval
    PreHeatingFlame(flame, flameSeries.stratum.level, startTime, endTime)
  }

  /**
   * Returns flames generated from a plant ignition run.
   *
   * If there was no ignition an empty collection is returned.
   */
  private def getPlantFlames(plantRunResult: IgnitionRunResult, stratumWindSpeed: Double): IndexedSeq[Flame] = {
    if (!plantRunResult.isIgnition) Vector.empty
    else {
      val avWidth = plantRunResult.stratum.averageWidth
      val plantSep = plantRunResult.stratum.modelPlantSep

      val mergedFlameLengths = plantRunResult.flameAttr.flameLengths map { flen =>
        Flame.lateralMergedFlameLength(flen, fireLineLength, avWidth, plantSep)
      }

      val flames = (0 until plantRunResult.flameAttr.size) map { i =>
        val length = mergedFlameLengths(i)
        val angle = Flame.windEffectFlameAngle(length, stratumWindSpeed, site.slope)
        Flame(length, angle, plantRunResult.flameAttr.origins(i), plantRunResult.flameAttr.flameDepths(i), plantRunResult.flameAttr.temperatures(i))
      }

      flames
    }
  }

  /**
   * Returns flames generated from a stratum ignition run.
   *
   * If there was no ignition an empty collection is returned.
   */
  private def getStratumFlames(stratumRunResult: IgnitionRunResult, stratumWindSpeed: Double): IndexedSeq[Flame] = {
    if (!stratumRunResult.isIgnition) Vector.empty
    else {
      val attr = stratumRunResult.flameAttr
      val flames = (0 until attr.size) map { i =>
        val angle = Flame.windEffectFlameAngle(attr.flameLengths(i), stratumWindSpeed, site.slope)
        Flame(attr.flameLengths(i), angle, attr.origins(i), attr.flameDepths(i), attr.temperatures(i))
      }

      flames
    }
  }

  /**
   * Runs the ignition path simulation for all species in the given stratum.
   */
  private def findPlantIgnitionPaths(stratum: Stratum, context: IgnitionContext): IgnitionRunResult = {
    // Create function to generate ignition paths
    val pathFn = pathModel.generatePath(context, plantFlameModel) _

    // Simulate paths for each species in the stratum, selecting the 'best'
    // path for each
    val bestPaths = stratum.speciesComponents map { spComp =>
      val initPts = initialCrownIgnitionPoints(spComp.species)
      val paths = initPts map (p => pathFn(spComp, p))
      paths reduceLeft selectBestPath
    }

    val isIgnition = bestPaths exists (_.hasIgnition)
    val flameAttr = WeightedFlameAttributes(plantFlameModel)(bestPaths)

    new IgnitionRunResult(stratum, bestPaths, isIgnition, flameAttr)
  }

  /**
   * Runs the ignition paths simulation for all species in the given stratum
   * using an artificial crown based on average stratum canopy attributes.
   */
  private def findStratumIgnitionPaths(stratum: Stratum, context: IgnitionContext, referenceFlame: Flame): IgnitionRunResult = {
    val stratumCrown = createStratumCrown(stratum)
    val ray = Ray(referenceFlame.origin, referenceFlame.angle)
    val crossing = stratumCrown.intersection(ray)

    if (crossing.isEmpty) new IgnitionRunResult(stratum, Vector.empty, false, WeightedFlameAttributes.Empty)
    else {
      val pathFn = pathModel.generatePath(context, plantFlameModel) _
      val initialPt = crossing.get.start

      val paths = for {
        spComp <- stratum.speciesComponents
        proxySpecies = createProxyStratumSpecies(spComp.species, stratumCrown, stratum)
        proxyComponent = SpeciesComponent(proxySpecies, spComp.weighting)
        path = pathFn(proxyComponent, crossing.get.start)
      } yield path

      val isIgnition = paths exists (_.hasIgnition)

      val flameAttr = WeightedFlameAttributes(plantFlameModel)(paths)

      new IgnitionRunResult(stratum, paths, isIgnition, flameAttr)
    }
  }

  /**
   * Compares two IgnitionPaths and selects the 'best'.
   *
   * If only one path has ignition it is selected. If both have ignition, the path
   * with the longest ignited segment is selected. If neither have ignition,
   * the path with the highest drying temperature is selected.
   */
  private def selectBestPath(a: IgnitionPath, b: IgnitionPath): IgnitionPath = {
    if (a.hasIgnition) {
      if (b.hasIgnition)
        if (Numerics.gt(b.maxSegmentLength, a.maxSegmentLength)) b else a
      else a
    } else if (b.hasIgnition) b
    else if (Numerics.gt(b.maxDryingTemperature, a.maxDryingTemperature)) b else a
  }

  /**
   * Creates an artificial crown for a stratum flame run.
   */
  def createStratumCrown(stratum: Stratum): CrownPoly = {
    val minx = stratum.modelPlantSep - stratum.averageWidth / 2
    val maxx = minx + StratumBigCrownWidth

    val tanSlope = math.tan(site.slope)

    val vertices = Vector(
      Coord(minx, stratum.averageBottom + minx * tanSlope),
      Coord(minx, stratum.averageTop + minx * tanSlope),
      Coord(maxx, stratum.averageTop + maxx * tanSlope),
      Coord(maxx, stratum.averageBottom + maxx * tanSlope))

    CrownPoly(vertices)
  }

  /**
   * Creates a proxy for a given species to use in a stratum flame run.
   */
  def createProxyStratumSpecies(sp: Species, crown: CrownPoly, stratum: Stratum): Species = {
    Species(name = sp.name,
      crown = crown,
      liveLeafMoisture = sp.liveLeafMoisture,
      deadLeafMoisture = sp.deadLeafMoisture,
      propDead = sp.propDead,
      ignitionTemp = Some(sp.ignitionTemperature),
      leafForm = sp.leafForm,
      leafThickness = sp.leafThickness,
      leafWidth = sp.leafWidth,
      leafLength = sp.leafLength,
      leafSeparation = sp.leafSeparation,
      stemOrder = sp.stemOrder,
      clumpDiameter = sp.crown.width,
      clumpSeparation = math.max(sp.clumpSeparation, stratum.modelPlantSep - stratum.averageWidth))
  }

  /**
   * Returns the coordinates of candidate ignition points across the base of
   * a species crown.
   */
  def initialCrownIgnitionPoints(species: Species): IndexedSeq[Coord] = {
    val pts = for {
      prop <- -1.0 to 1.0 by 0.5
      x = species.crown.width * prop / 2
      crownPt = species.crown.pointInBase(x)

      // ensure that the point is not below surface
      surfacePt = Coord(x, x * math.tan(site.slope))
      pt = if (crownPt.y > surfacePt.y) crownPt else surfacePt
    } yield pt

    pts
  }

  /**
   * Tests if any ignited segments in an IgnitionPath give rise to flames extending
   * beyond the species crown.
   *
   * TODO: this calculation is based on ignited segment but possibly duplicates code
   * in the main run function where we are creating plant flames. Check with Phil
   * about this.
   */
  private def isFlameBeyondCrown(path: IgnitionPath, windSpeed: Double): Boolean = {
    path.segmentsByLengthAndTime exists { seg =>
      val species = path.speciesComponent.species
      val flameLen = plantFlameModel.flameLength(species, seg.length)
      val flameTipX = seg.start.x + flameLen * math.cos(Flame.windEffectFlameAngle(flameLen, windSpeed, site.slope))
      flameTipX > species.crown.width / 2
    }
  }

  /**
   * Creates incident flames acting on the given stratum.
   *
   * Compute incident flames for this stratum from surface flames and the
   * flames from lower strata. Only include lower strata which have a connection
   * to this stratum.
   */
  private def createIncidentFlames(
    stratum: Stratum,
    allFlameSeries: IndexedSeq[StratumFlameSeries],
    flameConnections: FlameConnections): IndexedSeq[Flame] = {

    if (allFlameSeries.isEmpty) surfaceParams.flames
    else {
      // Find lower strata with flames and a connection to the current stratum
      val lowerStrata =
        for {
          otherStratum <- site.vegetation.strata
          if otherStratum < stratum &&
            allFlameSeries.exists(fs => fs.stratum.level == otherStratum.level) &&
            flameConnections.isConnected(lower = otherStratum, upper = stratum)
        } yield otherStratum

      combineStrataFlames(lowerStrata, allFlameSeries)
    }
  }
  
  /**
   * Returns combined flames for all strata, including the canopy, from surface
   * flames and stratum flames.
   * 
   * Only strata which have a connection to the canopy (flame connection or
   * vertical association) are included.
   */
  private def combinedFlamesForAllStrata(
      allFlameSeries: IndexedSeq[StratumFlameSeries],
      flameConnections: FlameConnections): IndexedSeq[Flame] = {
    
    if (allFlameSeries.isEmpty) surfaceParams.flames
    else {
      val canopy = site.vegetation.strataByLevel(StratumLevel.Canopy)
      val strataConnectedToCanopy =
        for {
          s <- site.vegetation.strata
          if stratumLevelHasFlames(s.level, allFlameSeries) &&
            (s.level == StratumLevel.Canopy || flameConnections.isConnected(lower = s, upper = canopy))
        } yield s
        
      combineStrataFlames(strataConnectedToCanopy, allFlameSeries)
    }
  }
  
  private def stratumLevelHasFlames(level: StratumLevel, allFlameSeries: IndexedSeq[StratumFlameSeries]): Boolean =
    allFlameSeries exists (fs => fs.stratum.level == level)

  /**
   * Derives combined flames from the given strata.
   */
  private def combineStrataFlames(
    strata: IndexedSeq[Stratum],
    allFlameSeries: IndexedSeq[StratumFlameSeries]): IndexedSeq[Flame] = {

    val flameSeriesByLevel = Map() ++ (allFlameSeries map (fs => (fs.stratum.level, fs)))

    // Calculate a flame-weighted wind speed
    val initLen = surfaceParams.flameLength
    val initWind = surfaceParams.windSpeed * initLen

    val (finalLen, finalWind) =
      strata.foldLeft((initLen, initWind)) {
        case ((curLen, curWind), lower) =>
          val flameLen = flameSeriesByLevel(lower.level).cappedMaxFlameLength
          val windSp = VegetationWindModel.windSpeedAtHeight(lower.averageMidHeight, site, includeCanopy)

          (curLen + flameLen, curWind + flameLen * windSp)
      }

    val flameWeightedWindSpeed = if (finalLen > 0) finalWind / finalLen else 0.0

    /*
       * Combine surface flames with those from lower active strata to 
       * create the incident flames
       */
    val combinedFlames: IndexedSeq[Flame] = strata.foldLeft(surfaceParams.flames) {
      case (flames, lower) =>
        val lowerFlames = flameSeriesByLevel(lower.level).flames

        Flame.combineFlames(flames, lowerFlames, flameWeightedWindSpeed, site.slope, fireLineLength)
    }

    // Return the combined flames as the incident flames
    combinedFlames
  }

  /**
   * Calculates canopy heating distance given the canopy stratum and the collection
   * of flame series for lower strata.
   */
  def calculateCanopyHeatingDistance(canopyStratum: Stratum, allFlameSeries: IndexedSeq[StratumFlameSeries]): Double = {

    require(canopyStratum.level == StratumLevel.Canopy)  // just in case

    // Check that we haven't somehow got a flame series for the canopy already
    require(!allFlameSeries.exists(_.stratum.level == StratumLevel.Canopy), "Flame series already created for canopy stratum")

    val canopyLine = Line(Coord(0.0, canopyStratum.averageBottom), site.slope)

    /*
     * Recursive helper to process the flame series sequence.
     * Returns the calculated canopy heating distance when finished.
     */
    def iter(fss: IndexedSeq[StratumFlameSeries], curDist: Double): Double = {
      if (fss.isEmpty) curDist
      else {
        val flame = fss.head.longestFlame
        
        val pt = canopyLine.intersection(flame.plume) match {
          // flame intersects lower canopy edge - return point  
          case Some(coord) => coord
          
          // no intersection - flame origin must be above lower canopy
          // edge (overlapping strata) so use origin as intersection point
          case None => flame.origin 
        }

        val nextDist = {
          val d = flame.origin.distanceTo(pt)
          if (flame.plumeTemperature(d, site.temperature) >= MinTempForCanopyHeating)
            curDist max pt.x
          else
            curDist
        }

        iter(fss.tail, nextDist)
      }
    }

    iter(allFlameSeries, 0.0)
  }

}

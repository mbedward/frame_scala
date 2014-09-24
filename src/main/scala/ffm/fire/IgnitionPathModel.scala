package ffm.fire

import scala.collection.mutable.ArrayBuffer
import ffm.ModelSettings
import ffm.forest.Site
import ffm.forest.Species
import ffm.forest.StratumLevel
import ffm.geometry.Coord
import ffm.geometry.Line
import ffm.geometry.Ray
import ffm.forest.VegetationWindModel
import ffm.forest.Stratum


trait IgnitionPathModel {
  
  def generatePath(runType: IgnitionRunType)(
    site: Site,
    stratumLevel: StratumLevel,
    species: Species,
    incidentFlames: Vector[Flame],
    preHeatingFlames: Vector[PreHeatingFlame],
    preHeatingEndTime: Double,
    canopyHeatingDistance: Double,
    stratumWindSpeed: Double,
    initialPoint: Coord): IgnitionResult
}


sealed trait IgnitionRunType
object IgnitionRunType {
  case object PlantRun extends IgnitionRunType
  case object StratumRun extends IgnitionRunType  
}


class SingleSiteIgnitionPathModel extends IgnitionPathModel {
  
  import IgnitionRunType._

  /**
   * Models an ignition path for a plant canopy or stratum pseudo-canopy.
   */
  def generatePath(runType: IgnitionRunType)(
    site: Site,
    stratumLevel: StratumLevel,
    species: Species,
    incidentFlames: Vector[Flame],
    preHeatingFlames: Vector[PreHeatingFlame],
    preHeatingEndTime: Double,
    canopyHeatingDistance: Double,
    stratumWindSpeed: Double,
    initialPoint: Coord): IgnitionResult = {

    // Array buffer to accumulate plant flames as ignition proceeds
    val plantFlames = ArrayBuffer.empty[Flame]

    ///////////////////////////////////////////////////////////////////////////
    //
    // Helper functions 
    //
    ///////////////////////////////////////////////////////////////////////////

    /*
     * Calculates the maximum path length that can be ignited by a plant flame, if present.
     */
    def calculateMaxPlantPathLength(plantFlame: Option[Flame], curPoint: Coord): Double =
      (for {
        flame <- plantFlame

        // intersection of the flame's path with the crown (may be None)
        r = Ray(curPoint, flame.angle)
        seg <- species.crown.intersection(r)

        // max distance from the flame with the require ignition temp (may be None)
        ignitLen <- flame.distanceForTemperature(species.ignitionTemperature, site.temperature)

        // resulting path length
        pathLen = math.min(seg.length, ignitLen)

      } yield pathLen).getOrElse(0.0)

      
    /*
     * Calculates the maximum path length that can be ignited by an incident flame, if present.
     */
    def calculateMaxIncidentPathLength(incidentFlame: Option[Flame], curPoint: Coord): Double = {
      (for {
        flame <- incidentFlame
        surfaceLine = Line(Coord(0, 0), site.slope)

        flameOrigin: Coord = runType match {
          case PlantRun =>
            surfaceLine.originOnLine(curPoint, flame.angle).getOrElse(
              throw new Error("Unable to find incident flame origin"))

          case StratumRun => flame.origin
        }

        r = Ray(curPoint, flame.angle)
        seg <- species.crown.intersection(r)

        distForTemp <- flame.distanceForTemperature(species.ignitionTemperature, site.temperature)
        ignitDist = math.max(0.0, distForTemp - curPoint.distanceTo(flameOrigin))

        pathLen = math.min(seg.length, ignitDist)

      } yield pathLen).getOrElse(0.0)
    }
    
    
    /*
     * Finds the farthest point at which ignition can occur in this time step, if any.
     */
    def findNextIgnitionEndPoint(timeStep: Int,
        incidentFlame: Option[Flame], plantFlame: Option[Flame], 
        pathLength: Double, pathAngle: Double, curPoint: Coord): Option[Coord] = {
      
      // Helper to determine if a test point is ignitable
      def canIgnite(testPoint: Coord): Boolean = {
        val dryingFactor = computeDryingFactor(curPoint, testPoint, timeStep)

        val incidentTemp = (for {
          flame <- incidentFlame
          origin = locateFlameOrigin(flame, testPoint)
          t = flame.plumeTemperature(testPoint.distanceTo(origin), site.temperature)
        } yield t).getOrElse(0.0)

        val plantTemp = (for {
          flame <- plantFlame
          t = flame.plumeTemperature(testPoint.distanceTo(flame.origin), site.temperature)
        } yield t).getOrElse(0.0)

        val maxTemp = math.max(incidentTemp, plantTemp)

        if (maxTemp < species.ignitionTemperature)
          false
        else if (dryingFactor * calculateIDT(maxTemp) > ModelSettings.ComputationTimeInterval)
          false
        else
          true
      }

      // Generate test points and return the further ignitable point, or None
      val stepDist = pathLength / ModelSettings.NumPenetrationSteps
      val testPoints = (stepDist to pathLength by stepDist) map (d => curPoint.toBearing(pathAngle, d))
      testPoints.takeWhile(canIgnite).lastOption
    }

    
    /*
     * Computes a drying factor (0: complete drying to 1: no drying) due to pre-heating,
     * incident and plant flames.
     */
    def computeDryingFactor(curPoint: Coord, testPoint: Coord, timeStep: Int): Double = {
      var df = dryingFromPreheatingFlames(curPoint, testPoint)
      if (df > 0.0) df *= dryingFromIncidentFlames(curPoint, testPoint, timeStep)
      if (df > 0.0) df *= dryingFromPlantFlames(curPoint, testPoint)
      df
    }

    
    /*
     * Returns cumulative drying factor due to the given pre-heating flames at a target point.
     */
    def dryingFromPreheatingFlames(curPoint: Coord, testPoint: Coord): Double = {
      // The last pre-heating flame is not considered (it will provide direct heating)
      if (preHeatingFlames.size < 2) 1.0 // no drying
      else {
        val locatedFlames = for {
          phf <- preHeatingFlames.init // skipping last flame
          line = Line(phf.flame.origin, site.slope)
          origin <- line.originOnLine(curPoint, phf.flame.angle)
        } yield phf.toOrigin(origin)

        val dryingPerFlame = for {
          phf <- locatedFlames
          idt = calculateFlameIDT(phf.flame, locateFlameOrigin(phf.flame, curPoint), testPoint)
          duration = phf.duration(preHeatingEndTime)
        } yield math.max(0.0, 1.0 - duration / idt)

        dryingPerFlame.product
      }
    }

    
    /*
     * Returns cumulative drying factor due to the given incident flames.
     * 
     * - curPoint is the point reached by ignition so far (or the initial point if 
     *   we have just started) 
     * - testPoint is the the point being tested for ignition
     */
    def dryingFromIncidentFlames(curPoint: Coord, testPoint: Coord, timeStep: Int): Double = {
      if (incidentFlames.isEmpty) 1.0 // no drying
      else {
        val N = math.min(timeStep - 1, incidentFlames.size)

        val dryingPerFlame = for {
          i <- 1 to N
          flame = incidentFlames(i - 1)
          idt = calculateFlameIDT(flame, locateFlameOrigin(flame, curPoint), testPoint)
        } yield math.max(0.0, 1.0 - ModelSettings.ComputationTimeInterval / idt)

        dryingPerFlame.product
      }
    }

    
    /*
     * Returns cumulative drying factor due to the given plant flames.
     * 
     * - curPoint is the point reached by ignition so far (or the initial point if 
     *   we have just started) 
     * - testPoint is the the point being tested for ignition
     */
    def dryingFromPlantFlames(curPoint: Coord, testPoint: Coord): Double = {
      if (plantFlames.isEmpty) 1.0 // no drying
      else {
        val dryingPerFlame = for {
          flame <- plantFlames
          // Use the plant flame origin in the IDT calculation
          idt = calculateFlameIDT(flame, flame.origin, testPoint)
        } yield math.max(0.0, 1.0 - ModelSettings.ComputationTimeInterval / idt)

        dryingPerFlame.product
      }
    }

    
    /*
     * Calculates an ignition delay time due to the given flame.
     */
    def calculateFlameIDT(flame: Flame, flameOriginForCalc: Coord, testPoint: Coord): Double = {
      val d = testPoint.distanceTo(flameOriginForCalc)
      val t = flame.plumeTemperature(d, site.temperature)
      calculateIDT(t)
    }

    
    /*
     * Calculates an ignition delay time at the given temperature.
     */
    def calculateIDT(temperature: Double): Double = {
      val idtProp =
        if (Species.isGrass(species, stratumLevel)) ModelSettings.GrassIDTReduction
        else 1.0

      species.ignitionDelayTime(temperature) * idtProp
    }

    
    /*
     * Locates the origin at ground level for a flame such that its path will
     * pass through the given target point.
     */
    def locateFlameOrigin(flame: Flame, targetPoint: Coord): Coord = {
      runType match {
        case PlantRun =>
          val surfaceLine = Line(Coord.Origin, site.slope)

          surfaceLine.originOnLine(targetPoint, flame.angle).getOrElse(
            throw new Error("Unable to find flame origin"))

        case StratumRun => flame.origin
      }
    }

    
    /*
     * Creates a new plant flame based on the given ignited segment
     */
    def newPlantFlame(start: Coord, end: Coord, modifiedWindSpeed: Double): Flame = {
      val len = start.distanceTo(end)
      require(len > 0)

      val flameLen = species.flameLength(len)
      val deltaT =
        if (Species.isGrass(species, stratumLevel)) ModelSettings.GrassFlameDeltaTemperature
        else ModelSettings.MainFlameDeltaTemperature

      Flame(flameLen,
        Flame.windEffectFlameAngle(flameLen, modifiedWindSpeed, site.slope),
        start,
        len,
        deltaT)
    }

    
    ///////////////////////////////////////////////////////////////////////////
    //
    // The main loop
    //
    ///////////////////////////////////////////////////////////////////////////

    var curPoint = initialPoint
    var isFinished = false
    var igResult = IgnitionResult(species, stratumLevel, site)

    def timeSinceIgnition(curTime: Int): Int = igResult.ignitionTime match {
      case None => 0
      case Some(t) => curTime - t
    }

    var timeStep = 0
    while (!isFinished && timeSinceIgnition(timeStep) < ModelSettings.MaxIgnitionTimeSteps) {
      timeStep += 1
      
      val modifiedWindSpeed = {
        if (runType == StratumRun && igResult.hasIgnition) {
          val n = igResult.segments.size
          val xstart =
            if (n == 1) initialPoint.x
            else igResult.segments(n - 2).end.x

          val xend =
            if (n == 1) igResult.segments(0).end.x
            else igResult.segments(n - 1).end.x

          stratumWindSpeed - math.max(0.0, xend - xstart) / ModelSettings.ComputationTimeInterval

        } else {
          stratumWindSpeed
        }
      }

      // Plant flame from previous time step
      val plantFlame: Option[Flame] = plantFlames.lastOption

      // Incident flame
      val incidentFlame: Option[Flame] =
        if (timeStep > incidentFlames.size) None
        else Some(incidentFlames(timeStep - 1))

      if ( plantFlame.isEmpty && incidentFlame.isEmpty) {
        isFinished = true
        
      } else {
        val maxPlantPathLen = calculateMaxPlantPathLength(plantFlame, curPoint)
        val maxIncidentPathLen = calculateMaxIncidentPathLength(incidentFlame, curPoint)

        if (!(maxPlantPathLen > 0 || maxIncidentPathLen > 0)) {
          isFinished = true
          
        } else {
          // Take path length and angle from whichever flame gave the longest path length
          val (pathLength, pathAngle) =
            if (maxPlantPathLen > maxIncidentPathLen) (maxPlantPathLen, plantFlame.get.angle)
            else (maxIncidentPathLen, incidentFlame.get.angle)

          val nextPointOp = findNextIgnitionEndPoint(timeStep, incidentFlame, plantFlame, pathLength, pathAngle, curPoint)
          
          if (nextPointOp.isEmpty) {
            isFinished = true

          } else {
            val nextIgnitablePoint = nextPointOp.get
            
            if (!igResult.hasIgnition) {
              igResult = igResult.withSegment(timeStep, start = curPoint, end = nextIgnitablePoint)
              plantFlames += newPlantFlame(start = curPoint, end = nextIgnitablePoint, modifiedWindSpeed)

            } else {
              //compute flame duration and hence start point of new segment
              val flameDuration =
                if (runType == StratumRun &&
                  stratumLevel == StratumLevel.Canopy &&
                  curPoint.x > canopyHeatingDistance) {
                  // Flame residence time is reduced for stratum ignition in canopy 
                  // if the canopy has not been heated sufficiently.
                  math.ceil(ModelSettings.ReducedCanopyFlameResidenceTime / ModelSettings.ComputationTimeInterval).toInt
                } else {
                  math.ceil(species.flameDuration / ModelSettings.ComputationTimeInterval).toInt
                }

              val segStart = {
                val n = igResult.segments.size
                if (n < flameDuration) igResult.segments.head.start
                else igResult.segments(n - flameDuration).end
              }

              if (segStart.distinctFrom(nextIgnitablePoint)) {
                igResult = igResult.withSegment(timeStep, segStart, nextIgnitablePoint)
                plantFlames += newPlantFlame(segStart, nextIgnitablePoint, modifiedWindSpeed)
              } else
                isFinished = true
            }

            if (!isFinished) {
              curPoint = nextIgnitablePoint
            }
          }
        }
      } 
    }

    // Return result
    igResult
  }

}
package ffm.forest

import ffm.ModelSettings
import ffm.geometry.CrownPoly
import ffm.numerics.Numerics
import ffm.util.Options

/**
 * Species.
 * 
 * This has a private constructor to force use of the Species object factory method
 * which does sanity checking and tweaking of argument values.
 */
class Species private (
    val name: String,
    val crown: CrownPoly,
    val liveLeafMoisture: Double,
    val deadLeafMoisture: Double,
    val propDead: Double,
    val propSilicaFreeAsh: Option[Double],
    val ignitionTemperatureProvided: Option[Double],
    val leafForm: LeafForm,
    val leafThickness: Double,
    val leafWidth: Double,
    val leafLength: Double,
    val leafSeparation: Double,
    val stemOrder: Double,
    val clumpDiameter: Double,
    val clumpSeparation: Double
    ) {
  
  val propLive = 1.0 - propDead
  
  val leafArea = leafWidth * leafLength / 2.0
  
  val leafMoisture = propLive * liveLeafMoisture + propDead * deadLeafMoisture
  
  val flameDuration = math.max(
    1.37 * leafWidth * leafThickness * 1.0e6 + 1.61 * leafMoisture - 0.027, 
    ModelSettings.ComputationTimeInterval )
    
  /** 
   * Modelled ignition temperature (will be None if silica free ash proportion was not provided) 
   */
  val ignitionTemperatureModelled: Option[Double] = propSilicaFreeAsh map { prop => 
    val logPc = math.log(prop * 100)
    354.0 - 13.9 * logPc - 2.91 * logPc * logPc
  }

  /**
   * Ignition temperature: either the one provided for this species or 
   * one modelled from the proportion of silica free ash.
   */
  val ignitionTemperature: Double =
    (ignitionTemperatureProvided orElse ignitionTemperatureModelled).get

    
  /**
   * Ignition delay time for the given temperature.
   */
  def ignitionDelayTime(temperature: Double): Double = {
    val leafFactor = leafForm match {
      case LeafForm.Round => 4.0
      case _ => 2.0
    }
    
    val m = 100 * leafMoisture * leafThickness * 1000 / leafFactor
    
    100168.23 * math.pow(temperature, -2.11) * m + 6018087.86 * math.pow(temperature,-2.39)
  }

  /**
   * Estimated leaf flame length.
   */
  val leafFlameLength = {
    val sqRootArea = math.sqrt(leafArea)
    val cubeRootArea = math.cbrt(leafArea)
    if (leafMoisture < (17.5 * cubeRootArea - 52.5 * sqRootArea - 0.0027) / 0.277)
      1.75 * cubeRootArea - 0.0277 * leafMoisture - 0.00027
    else
      5.25 * sqRootArea
  }
  
  /**
   * Estimated leaf density.
   */
  val leavesPerClump = 0.88 * math.pow(clumpDiameter * stemOrder / leafSeparation, 1.18)

  /**
   * Estimated leaf area index.
   */
  val leafAreaIndex = {
    val clumpVolume = 4.0 / 3.0 * math.Pi * math.pow((clumpDiameter + clumpSeparation) / 2, 3)
    val groundArea = math.Pi * math.pow(crown.width / 2, 2)    
    
    leafArea * leavesPerClump * crown.volume / clumpVolume / groundArea
  }
  
  /**
   * Merged leaf flame length model (Zylstra thesis Eq 5.76).
   * We use the average of the number of clumps that will be 
   * traversed by the ignited segment (Eq 5.63).
   *
   * Does merging of leaf flame lengths but does not do lateral 
   * merging of plant flames.
   */
  def flameLength(lengthIgnitedSeg: Double): Double = {
    if (Numerics.almostZero(lengthIgnitedSeg)) 0.0
    else {
      val numLeaves = leavesPerClump * lengthIgnitedSeg / (clumpDiameter  + clumpSeparation )
      val term1 = math.pow(leafFlameLength * math.pow(numLeaves, 0.4) + lengthIgnitedSeg, 4.0)
      val term2 = math.pow(lengthIgnitedSeg, 4.0)
      math.max(lengthIgnitedSeg, math.pow(term1 + term2, 0.25) )
    }
  }
  
  override def toString = s"Species($name)"
}


/**
 * Companion object with a factory method to create Species instances
 * and check argument values.
 */
object Species {

  /**
   * Creates a new Species object.
   */
  def apply(
    name: String,
    crown: CrownPoly,
    liveLeafMoisture: Double,
    deadLeafMoisture: Double,
    propDead: Double,
    propSilicaFreeAsh: Option[Double] = None,
    ignitionTemp: Option[Double] = None,
    leafForm: LeafForm,
    leafThickness: Double,
    leafWidth: Double,
    leafLength: Double,
    leafSeparation: Double,
    stemOrder: Double,
    clumpDiameter: Double,
    clumpSeparation: Double): Species = {

    require(!name.trim().isEmpty(), "species name is required")
    
    import ffm.util.ArgUtils._
      
    isNotNegative("liveLeafMoisture", liveLeafMoisture)
    isNotNegative("deadLeafMoisture", deadLeafMoisture)
    
    isProportion("propDead", propDead, allowZero=true)
    
    require(Options.atLeast(1, propSilicaFreeAsh, ignitionTemp), 
      s"species $name has neither silica free ash proportion or ignition temp provided")
      
    propSilicaFreeAsh match {
      case Some(p) => isProportion("propSilicaFreeAsh", p, allowZero=false)
      case None => // no value provided
    }
    
    ignitionTemp match {
      case Some(t) => isPositive("ignitionTemp", t)
      case None => // no value provided
    }
    
    isNotNegative("leafThickness", leafThickness)
    isNotNegative("leafWidth", leafWidth)
    isNotNegative("leafLength", leafLength)
    isNotNegative("leafSeparation", leafSeparation)
    
    isNotNegative("stemOrder", stemOrder)
    isNotNegative("clumpDiameter", clumpDiameter)
    isNotNegative("clumpSeparation", clumpSeparation)
      
    new Species(name.trim(), crown, 
        liveLeafMoisture, deadLeafMoisture, propDead, 
        propSilicaFreeAsh, ignitionTemp, 
        leafForm, leafThickness, leafWidth, leafLength, leafSeparation, 
        stemOrder, clumpDiameter, clumpSeparation)  
  } 
  
  
  /**
   * Tests if a species should be treated as a grass.
   */
  def isGrass(sp: Species, stratumLevel: StratumLevel): Boolean =
    stratumLevel == StratumLevel.NearSurface && 
    sp.propDead > 0.5 &&
    sp.leafThickness < 0.00035
  
    
  
}


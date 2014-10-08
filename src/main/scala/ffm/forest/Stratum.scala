package ffm.forest

import ffm.geometry.CrownPoly
import ffm.fire.PlantFlameModel


class Stratum private (
  val level: StratumLevel,
  speciesComps: IndexedSeq[SpeciesComponent],
  val plantSep: Double) 
  
  extends Ordered[Stratum] {
  
  require(!speciesComps.isEmpty, "one or more SpeciesComponents required")
  require(plantSep >= 0, "plant separation must be >= 0")
  
  /** Compare this stratum with another on the basis of their levels. */
  override def compare(that: Stratum): Int = this.level.compare(that.level)
  
  /**
   * Species weightings with values normalized to proportion within stratum.
   */
  val speciesComponents = {
    val sumVals = (speciesComps map (_.weighting)).sum
    speciesComps.map( sc => SpeciesComponent(sc.species, sc.weighting / sumVals) )
  }
  
  /** Weighted average crown width. */
  val averageWidth = wtAv( sc => crown(sc).width )

  /** Weighted average crown height. */
  val averageTop = wtAv( sc => crown(sc).top )
  
  /** Weighted average crown base. */
  val averageBottom = wtAv( sc => crown(sc).bottom )
  
  /** Weighted average crown mid-height. */
  val averageMidHeight = wtAv( sc => (crown(sc).top + crown(sc).bottom) / 2 )
  
  /** Modelled plant separation. */
  val modelPlantSep = math.max(plantSep, averageWidth)
  
  /** Modelled canopy cover. */
  val cover = { 
    val p = averageWidth / modelPlantSep 
    p * p 
  }
  
  /** Modelled leaf area index. */
  val leafAreaIndex = cover * wtAv( sc => sc.species.leafAreaIndex )
  
  
  override def toString = 
    s"Stratum($level with ${speciesComponents.length} species)"
  
  
  /////////////////////////////////////////////////////////
  // Private helper functions
  /////////////////////////////////////////////////////////
  
  /**
   * Extracts the crown polygon from a SpeciesComponent.
   */
  private def crown(sc: SpeciesComponent) = sc.species.crown
  
  /** 
   *  Calculates composition-weighted average of a species attribute.
   *  
   *  Function f extracts the attribute value from each SpeciesComponent.
   */
  private def wtAv(f: SpeciesComponent => Double): Double = {
    (speciesComponents map (sc => sc.weighting * f(sc)) ).sum
  }
  
}

object Stratum {
  
  /**
   * Creates a Stratum object with a vector of SpeciesComponents.
   */
  def apply(level: StratumLevel, speciesComp: Seq[SpeciesComponent], plantSep: Double): Stratum =
    new Stratum(level, speciesComp.toVector, plantSep)
  
  /**
   * Creates a Stratum object with a single Species.
   */
  def apply(level: StratumLevel, species: Species, plantSep: Double): Stratum =
    new Stratum(level, Vector(SpeciesComponent(species, 1.0)), plantSep)
}
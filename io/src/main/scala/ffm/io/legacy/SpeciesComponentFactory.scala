package ffm.io.legacy

import scala.util.Try
import ffm.forest.DefaultSpecies
import ffm.forest.LeafForm
import ffm.forest.SpeciesComponent
import ffm.geometry.CrownPoly


object SpeciesComponentFactory {

  import ExpressionSyntax._
  import FactoryItem._

  val items = List(
    item("composition", "composition"),
    item("name", "name"),
    item("live leaf moisture", "liveLeafMoisture"),
    item("dead leaf moisture", "deadLeafMoisture"),
    item("ignition temperature", "ignitionTemp"),
    item("proportion dead", "propDead"),
    item("leaf form", "leafForm"),
    item("leaf thickness", "leafThickness"),
    item("leaf width", "leafWidth"),
    item("leaf length", "leafLength"),
    item("leaf separation", "leafSeparation"),
    item("stem order", "stemOrder"),
    item("clump separation", "clumpSeparation"),
    item("clump diameter", "clumpDiameter"),
    item("he", "he"),
    item("ht", "ht"),
    item("hc", "hc"),
    item("hp", "hp"),
    item("w", "w"))

    
  /**
   * Creates a SpeciesComposition object from a SpeciesDef and optional fallback parameters.
   * 
   * @param speciesDef species definition from a [[ModelDef]] object
   * @param fallbacks a FallbackProvider to query for parameters not found in the speciesDef
   */
  def create(speciesDef: SpeciesDef, fallbacks: FallbackProvider): Try[SpeciesComponent] = {
    for {
      vas <- Try(new ValueAssignments(speciesDef.params, items, fallbacks))
      species <- Try(buildSpecies(vas))
    } yield species
  }

  private def buildSpecies(vas: ValueAssignments): SpeciesComponent = {

    val sp = DefaultSpecies(
      name = vas.str("name"),
      crown = CrownPoly(hc = vas.dval("hc"), he = vas.dval("he"), ht = vas.dval("ht"), hp = vas.dval("hp"), w = vas.dval("w")),
      liveLeafMoisture = vas.dval("liveLeafMoisture"),
      deadLeafMoisture = vas.dval("deadLeafMoisture"),
      propDead = vas.dval("propDead"),
      ignitionTemp = vas.dval("ignitionTemp"),
      leafForm = LeafForm(vas.str("leafForm")),
      leafThickness = vas.dval("leafThickness"),
      leafWidth = vas.dval("leafWidth"),
      leafLength = vas.dval("leafLength"),
      leafSeparation = vas.dval("leafSeparation"),
      stemOrder = vas.dval("stemOrder"),
      clumpDiameter = vas.dval("clumpDiameter"),
      clumpSeparation = vas.dval("clumpSeparation"))

    SpeciesComponent(sp, weighting = vas.dval("composition"))
  }

}
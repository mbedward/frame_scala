package ffm.forest

import org.mockito.Mockito.when
import ffm.MockSpec
import ffm.geometry._
import ffm.util.IndexedSeqUtils._
import scala.Vector


class DefaultVegetationSpec extends MockSpec {
  
  import StratumLevel._

  /*
   * Canopy from 5 - 20m
   */
  val canopySpecies = mock[Species]
  val canopyLower = 5.0
  val canopyUpper = 20.0
  when(canopySpecies.crown) thenReturn (
    CrownPoly(hc = canopyLower, he = canopyLower, ht = canopyUpper, hp = canopyUpper, w = 10))

  val canopy = DefaultStratum(
    Canopy,
    canopySpecies,
    0.0)

  /*
   * Mid-storey from 2 - 8m
   */
  val midStoreySpecies = mock[Species]
  val midStoreyLower = 2.0
  val midStoreyUpper = 8.0
  when(midStoreySpecies.crown) thenReturn (
    CrownPoly(hc = midStoreyLower, he = midStoreyLower, ht = midStoreyUpper, hp = midStoreyUpper, w = 4))

  val midStorey = DefaultStratum(
    MidStorey,
    midStoreySpecies,
    0.0)

  /*
   * The site: slope = 0
   */
  val surface = mock[Surface]
  when(surface.slope) thenReturn (0.0)

  val weather = ConstantWeatherModel(temperature = 20.0, windSpeed = 30.0)

  val vegetation = DefaultVegetation(strata = Vector(midStorey, canopy), overlaps = Vector())
  
  val siteContext = SiteContext(fireLineLength = 100.0)
  
  val site = SingleSite(surface, vegetation, weather, siteContext)
  

  "DefaultVegetation" should "identify the correct vegetation layers" in {
    val layers = site.vegetation.layers(includeCanopy = true)

    // We expect three layers with veg and one empty layer at the bottom
    val expectedLayers = Vector(
      VegetationLayer(midStoreyUpper, canopyUpper, Vector(Canopy)),
      VegetationLayer(canopyLower, midStoreyUpper, Vector(MidStorey, Canopy)),
      VegetationLayer(midStoreyLower, canopyLower, Vector(MidStorey)),
      VegetationLayer(0.0, midStoreyLower, Vector.empty))

    layers should contain only (expectedLayers: _*)
  }

  it should "always identify the lowest layer as starting from ground level" in {
    val layers = site.vegetation.layers(includeCanopy = true)

    layers.last.lower should be(0.0)
  }

  it should "sort vegetation layers in descending order of height" in {
    val layers = site.vegetation.layers(includeCanopy = true)

    val upperHts = layers map (_.upper)
    for ((h, hnext) <- upperHts.pairs) h should be > hnext

    val lowerHts = layers map (_.lower)
    for ((h, hnext) <- lowerHts.pairs) h should be > hnext
  }

  it should "omit the canopy when requested" in {
    val layers = site.vegetation.layers(includeCanopy = false)
    
    for (layer <- layers) 
      layer.levels should not contain Canopy 
  }

}

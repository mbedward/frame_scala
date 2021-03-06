package ffm.forest

/** Marker trait for nature of overlap between a pair of strata. */
sealed trait StratumOverlapType

/**
 * Provides available definitions for overlaps between strata.
 */
object StratumOverlapType {
  case object Overlapping extends StratumOverlapType
  case object NotOverlapping extends StratumOverlapType
  case object Undefined extends StratumOverlapType

  /**
   * Retrieve a StratumOverlapType by name.
   *
   * Ignores case and any surrounding or embedded spaces and hyphens.
   *
   * {{{
   * val ov1 = StratmOverlapType("not overlapped")
   * val ov2 = StratumOverlapType("notoverlapped")
   *
   * ov1 == ov2  // will be `true`
   * }}}
   */
  def apply(name: String): StratumOverlapType = name.replaceAll("""[\s\-]+""", "").toLowerCase() match {
    case "overlapped" => Overlapping
    case "notoverlapped" => NotOverlapping
    case "automatic" => Undefined
    case s => throw new IllegalArgumentException("Not a valid stratum overlap type: " + s)
  }
}

/**
 * Stores the type of overlap between two stratum levels.
 */
case class StratumOverlap(lower: StratumLevel, upper: StratumLevel, overlapType: StratumOverlapType) {
  require(lower < upper)
}

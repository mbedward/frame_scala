package ffm.fire

/**
 * Holds ignition paths and flame series generated from plant and stratum
 * ignition runs, together with the surface fire parameters.
 */
case class FireModelRunResult (
    surfaceParams: SurfaceParams, 
    stratumOutcomes: IndexedSeq[StratumOutcome],
    combinedFlames: IndexedSeq[Flame]
    ) {
  
  /**
   * Creates an empty result object.
   */
  def this(surfaceParams: SurfaceParams) =
    this(surfaceParams, Vector.empty, Vector.empty)
  
  /**
   * The flame series with the largest flame for each stratum.
   */
  val flameSeriess: IndexedSeq[StratumFlameSeries] =
    stratumOutcomes.flatMap { outcome => 
      outcome.selectFlameSeries((fs1, fs2) => if (fs1.maxFlameLength > fs2.maxFlameLength) fs1 else fs2)
  }
    
  def withStratumOutcome(outcome: StratumOutcome) = copy(stratumOutcomes = stratumOutcomes :+ outcome)
  
  /** Sets the combined flames. */
  def withCombinedFlames(flames: IndexedSeq[Flame]) = copy(combinedFlames = flames)
  
}
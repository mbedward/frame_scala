package ffm.fire

import scala.collection.mutable.ArrayBuffer

import ffm.forest.SpeciesComponent
import ffm.geometry.Coord

/**
 * Used to progressively record results during an ignition path run and
 * then provide them as an immutable IgnitionPath object.
 */
trait IgnitionPathBuilder extends IgnitionPathBase {
  /** Records pre-heating flame drying prior to ignition. */
  def recordPreHeatingFlameDrying(
    time: Int,
    flame: PreHeatingFlame,
    distanceToFlame: Double,
    dryingFactor: Double,
    dryingTemperature: Double,
    duration: Double): Unit

  /** Records incident flame drying prior to ignition. */
  def recordIncidentFlameDrying(
    time: Int,
    flame: Flame,
    distanceToFlame: Double,
    dryingFactor: Double,
    dryingTemperature: Double,
    ignitionDelayTime: Double): Unit

  /** Adds a new segment. */
  def addSegment(timeStep: Int, start: Coord, end: Coord)

  /** The number of segments added so far. */
  def numSegments: Int

  /**
   * The first segment.
   *  @throws NoSuchElementException if no segments have been added.
   */
  def head: IgnitedSegment

  /**
   * The last segment.
   *  @throws NoSuchElementException if no segments have been added.
   */
  def last: IgnitedSegment

  /**
   * Returns an immutable IgnitionPath object based on the data held
   * by this builder.
   */
  def toIgnitionPath: IgnitionPath
}

/**
 * Companion object for the IgnitionPathBuilder trait.
 *
 * Usage:
 * {{{
 * // At the beginning of an ignition path simulation
 * val resultBuilder = IgnitionPathBuilder(runType, ignitionContext)
 *
 * // During the simulation
 * resultBuilder.addSegment(timeStep, startPoint, endPoint)
 *
 * // At the end of the simulation
 * resultBuilder.toIgnitionPath
 * }}}
 */
object IgnitionPathBuilder {
  def apply(context: IgnitionContext, speciesComponent: SpeciesComponent, initialPoint: Coord): IgnitionPathBuilder =
    new Builder(context, speciesComponent, initialPoint)

  private class Builder(val context: IgnitionContext, val speciesComponent: SpeciesComponent, val initialPoint: Coord) extends IgnitionPathBuilder {
    private val segmentBuffer = ArrayBuffer.empty[IgnitedSegment]
    private val preIgnitionBuffer = ArrayBuffer.empty[PreIgnitionData]

    /**
     *  Records pre-heating flame drying prior to ignition.
     */
    def recordPreHeatingFlameDrying(
      time: Int,
      flame: PreHeatingFlame,
      distanceToFlame: Double,
      dryingFactor: Double,
      dryingTemperature: Double,
      duration: Double) {

      assert(!hasIgnition, "Attempting to add pre-ignition data after ignition has been recorded")
      preIgnitionBuffer += new PreHeatingDrying(time, flame, distanceToFlame, dryingFactor, dryingTemperature, duration)
    }

    /** Records incident flame drying prior to ignition. */
    def recordIncidentFlameDrying(
      time: Int,
      flame: Flame,
      distanceToFlame: Double,
      dryingFactor: Double,
      dryingTemperature: Double,
      ignitionDelayTime: Double) {

      assert(!hasIgnition, "Attempting to add pre-ignition data after ignition has been recorded")
      preIgnitionBuffer += new IncidentDrying(time, flame, distanceToFlame, dryingFactor, dryingTemperature, ignitionDelayTime)
    }

    def preIgnitionData = preIgnitionBuffer.toVector
    def segments = segmentBuffer.toVector

    def addSegment(timeStep: Int, start: Coord, end: Coord) {
      if (!hasIgnition || timeStep == lastTimeStep + 1)
        segmentBuffer += new IgnitedSegment(timeStep, start, end)
      else
        throw new IllegalArgumentException(
          s"Time step for ignited segment ($timeStep) should be later than previous time (${lastTimeStep})")
    }

    def numSegments = segmentBuffer.size

    def head = segmentBuffer.head

    def last = segmentBuffer.last

    def toIgnitionPath = new BasicIgnitionPath(context, speciesComponent, initialPoint, preIgnitionData, segments)

  }

  private class BasicIgnitionPath(
    val context: IgnitionContext,
    val speciesComponent: SpeciesComponent,
    val initialPoint: Coord,
    val preIgnitionData: IndexedSeq[PreIgnitionData],
    val segments: IndexedSeq[IgnitedSegment]) extends IgnitionPath
}


package ffm.spike

import scala.util.{ Failure, Success }
import ffm.io._
import ffm.fire._
import ffm.util.FileUtils
import ffm.forest.StratumLevel

object BatchRunner {
  
  val paramDir = "c:/michael/coding/ffm/params"
  val paramExt = "txt"
  
  val paramFilesSubset = List("93a.txt")
  
  def main(args: Array[String]): Unit = {
    val allFiles = (new java.io.File(paramDir)).listFiles.filter(_.isFile).toList
    
    val paramFiles = 
      if (paramFilesSubset.isEmpty) allFiles
      else allFiles.filter { f => paramFilesSubset.contains(f.getName) }
    
    paramFiles.foreach { pf =>
      val name = pf.getPath
      if (name.endsWith(paramExt)) {
        println(name)
        run(name)
      }
    }
  }
  

  def run(paramPath: String, outputDir: String = "c:/michael/coding/ffm/testing"): Unit = {
    
    val result = Runner.run(paramPath)
    
    val outPath = {
      import FileUtils._
      for {
        inName <- fileName(paramPath)
        outName = removeExtension(inName) + "_out_scala.txt"
      } yield makePath(outputDir, outName)
    }

    if (outPath.isDefined) 
      FileUtils.withPrintWriter(outPath.get) { writer => writer.println(result) }
  }
}

object Runner {

  def run(paramPath: String): String = {

    val modelDef = ParamFileParser.readTextFormatFile(paramPath).get

    // get fallback value for dead leaf moisture from the surface 
    // dead fuel moisture parameter
    val deadFuelMoisture = new ValueAssignments(modelDef.params).dval("surface dead fuel moisture content")
    val fallback = FallbackProvider(Map("deadLeafMoisture" -> deadFuelMoisture))

    // create the site
    val site = SingleSiteFactory.create(modelDef, fallback) match {
      case Success(site) => site
      case Failure(t) => throw t
    }

    // run the fire model
    val pathModel = new SpikeIgnitionPathModel
    val plantFlameModel = DefaultPlantFlameModel

    // TODO: read fire line length from parameters file (check with Phil if it ever changes)
    val fireLineLength = 100.0
    
    val result = SingleSiteFireModelRunner.run(pathModel, plantFlameModel)(site, fireLineLength)    

    ResultFormatter.format(result)
  }
}

object PreIgnitionFormatter {
  val preHeatingDryingHeader =
    "Length\tDepth\tDist\tTemp\tDrying\tDuration"

  val incidentDryingHeader =
    "Length\tDepth\tDist\tTemp\tDrying\tIDT"

  def apply(data: PreIgnitionData): String = data match {
    case phd: PreHeatingDrying =>
      f"${phd.flameLength}%.2f\t${phd.depth}%.2f\t${phd.distanceToFlame}%.2f\t${phd.dryingTemperature}%.2f\t" +
        f"${phd.dryingFactor}%.4f\t${phd.duration}%.2f"

    case id: IncidentDrying =>
      f"${id.flameLength}%.2f\t${id.depth}%.2f\t${id.distanceToFlame}%.2f\t${id.dryingTemperature}%.2f\t" +
        f"${id.dryingFactor}%.4f\t${id.ignitionDelayTime}%.2f"
  }

}

object ResultFormatter {
  
  def adder(buf: StringBuilder): (String => Unit) = 
    s => buf ++= s + '\n'
  
  def format(fmr: FireModelResult): String = {
    val buf = new StringBuilder
    val add = adder(buf)
    
    fmr.stratumResults foreach { res => 
      add( s"${res.level}" )
      add( f"  flame length: ${res.flameLength}%.2f" )
      add( f"  flame angle:  ${res.flameAngle}%.2f" )
      add( f"  flame height:  ${res.flameHeight}%.2f" )
    }
    
    add( formatRunResult(fmr.run1) )
    
    if (fmr.hasSecondRun) {
      add( "\n\n=== Second run ===\n\n" )
      add( formatRunResult(fmr.run2) )
    }
    
    buf.toString
  }
    
  def formatRunResult(runResult: FireModelRunResult): String = {
    val buf = new StringBuilder    
    buf ++= formatSurfaceParams(runResult.surfaceParams) + '\n'
    
    runResult.stratumOutcomes foreach { outcome => buf ++= formatOutcome(outcome) + '\n' }
    
    buf.toString
  }
  
  def formatSurfaceParams(sp: SurfaceParams): String = {
    val buf = new StringBuilder
    val add = adder(buf)
    
    add( f"surface wind speed   ${sp.windSpeed}%.2f" )
    add( f"surface flame length ${sp.flameLength}%.2f" )
    add( f"surface flame angle  ${sp.flameAngle}%.2f" )
    add( f"surface flame height ${sp.flameHeight}%.2f" )

    buf.toString
  }

  def formatOutcome(outcome: StratumOutcome): String = {
    val buf = new StringBuilder
    val add = adder(buf)
   
    outcome.plantPaths foreach (path => add(formatPath(path)))
    outcome.stratumPaths foreach (path => add(formatPath(path)))
    
    buf.toString
  }
    
    
  def formatPath(path: IgnitionPath): String = {
    val buf = new StringBuilder
    val ctxt = path.context
    
    val add = adder(buf)

    add(s"${ctxt.stratumLevel} ${ctxt.runType}: ${path.speciesComponent.species.name}")

    add(s"Best result for initial point ${path.initialPoint}")

    val preHeating = path.preIgnitionData.filter(_.isInstanceOf[PreHeatingDrying])
    if (!preHeating.isEmpty) {
      add("")
      add(PreIgnitionFormatter.preHeatingDryingHeader)
      preHeating foreach { pid =>
        add(PreIgnitionFormatter(pid))
      }
    }

    val incident = path.preIgnitionData.filter(_.isInstanceOf[IncidentDrying])
    if (!incident.isEmpty) {
      add("")
      add(PreIgnitionFormatter.incidentDryingHeader)
      incident foreach { pid =>
        add(PreIgnitionFormatter(pid))
      }
    }

    if (path.hasIgnition) {
      add("\nTime\tStartX\tStartY\tEndX\tEndY\tSegLen")

      path.segments foreach { seg =>
        add(f"${seg.timeStep}\t${seg.start.x}%.2f\t${seg.start.y}%.2f\t${seg.end.x}%.2f\t${seg.end.y}%.2f\t${seg.length}%.2f")
      }
    }

    buf.toString
  }
}
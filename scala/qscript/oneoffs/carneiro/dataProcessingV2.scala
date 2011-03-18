package oneoffs.carneiro

import org.broadinstitute.sting.queue.extensions.gatk._
import org.broadinstitute.sting.queue.extensions.picard.PicardBamJarFunction
import org.broadinstitute.sting.queue.QScript
import org.broadinstitute.sting.queue.function.ListWriterFunction


class dataProcessingV2 extends QScript {
  qscript =>

  @Input(doc="path to GenomeAnalysisTK.jar", shortName="gatk", required=true)
  var GATKjar: File = _

  @Input(doc="path to AnalyzeCovariates.jar", shortName="ac", required=true)
  var ACJar: File = _

  @Input(doc="path to Picard's MarkDuplicates.jar", shortName="dedup", required=true)
  var dedupJar: File = _

  @Input(doc="path to Picard's MergeSamFiles.jar", shortName="merge", required=true)
  var mergeBamJar: File = _

  @Input(doc="path to R resources folder inside the Sting repository", shortName="r", required=true)
  var R: String = _

  @Input(doc="input BAM file - or list of BAM files", shortName="i", required=true)
  var input: File = _

  @Input(doc="Reference fasta file", shortName="R", required=false)
  var reference: File = new File("/seq/references/Homo_sapiens_assembly19/v1/Homo_sapiens_assembly19.fasta")

  @Input(doc="dbsnp ROD to use (VCF)", shortName="D", required=false)
  var dbSNP: File = new File("/humgen/gsa-hpprojects/GATK/data/dbsnp_132_b37.leftAligned.vcf")

  @Input(doc="extra VCF files to use as reference indels for Indel Realignment", shortName="indels", required=false)
  var indels: File = new File("/humgen/gsa-hpprojects/GATK/data/Comparisons/Unvalidated/AFR+EUR+ASN+1KG.dindel_august_release_merged_pilot1.20110126.sites.vcf")

  @Input(doc="the project name determines the final output (BAM file) base name. Example NA12878 yields NA12878.processed.bam", shortName="p", required=false)
  var projectName: String = "combined"

  @Input(doc="Perform cleaning on knowns only", shortName="knowns", required=false)
  var knownsOnly: Boolean = false

  @Input(doc="Perform cleaning on using Smith Waterman", shortName="sw", required=false)
  var useSW: Boolean = false

  @Input(doc="output path", shortName="outputDir", required=false)
  var outputDir: String = ""

  @Input(doc="the -L interval string to be used by GATK - output bams at interval only", shortName="L", required=false)
  var intervalString: String = ""

  @Input(doc="output bams at intervals only", shortName="intervals", required=false)
  var intervals: File = _

  val queueLogDir: String = ".qlog/"


  def script = {

    // Helpful variables
    val outName: String         = qscript.outputDir + qscript.projectName
    
    // BAM files generated by the pipeline
    val joinedBams = new File(outName + ".join.bam")
    val cleanedBam = new File(outName + ".clean.bam")
    val dedupedBam = new File(outName + ".clean.dedup.bam")
    val recalBam   = new File(outName + ".clean.dedup.recal.bam")

    // Accessory files
    val targetIntervals = new File(outName + ".intervals")
    val metricsFile     = new File(outName + ".metrics")
    val preRecalFile    = new File(outName + ".pre_recal.csv")
    val postRecalFile   = new File(outName + ".post_recal.csv")
    val preOutPath      = new File(outName + ".pre")
    val postOutPath     = new File(outName + ".post")


    //todo -- process bam headers to compile bamLists of samples.



    add(joinBams(input, joinedBams),
        target(joinedBams, targetIntervals),
        clean(joinedBams, targetIntervals, cleanedBam),
        dedup(cleanedBam, dedupedBam, metricsFile),
        cov(dedupedBam, preRecalFile),
        recal(dedupedBam, preRecalFile, recalBam),
        cov(recalBam, postRecalFile),
        analyzeCovariates(preRecalFile, preOutPath),
        analyzeCovariates(postRecalFile, postOutPath))
  }

  // General arguments to all programs
  trait CommandLineGATKArgs extends CommandLineGATK {
    this.jarFile = qscript.GATKjar
    this.reference_sequence = qscript.reference
    this.memoryLimit = Some(4)
    this.isIntermediate = true
  }

  case class joinBams (inBams: File, outBam: File) extends PicardBamJarFunction {
    @Input(doc="input bam list") var join = inBams
    @Output(doc="joined bam") var joined = outBam
    @Output(doc="joined bam index") var joinedIndex = new File(outBam + "bai")
    override def inputBams = List(join)
    override def outputBam = joined
    override def commandLine = super.commandLine + " CREATE_INDEX=true"
    this.memoryLimit = Some(6)
    this.jarFile = qscript.mergeBamJar
    this.isIntermediate = true
    this.jobName = queueLogDir + outBam + ".joinBams"
  }

  case class target (inBams: File, outIntervals: File) extends RealignerTargetCreator with CommandLineGATKArgs {
    if (!knownsOnly)
      this.input_file :+= inBams
    this.out = outIntervals
    this.mismatchFraction = Some(0.0)
    this.rodBind :+= RodBind("dbsnp", "VCF", dbSNP)
    this.rodBind :+= RodBind("indels", "VCF", indels)
    this.jobName = queueLogDir + outIntervals + ".target"
  }

  case class clean (inBams: File, tIntervals: File, outBam: File) extends IndelRealigner with CommandLineGATKArgs {
    this.input_file :+= inBams
    this.targetIntervals = tIntervals
    this.out = outBam
    this.rodBind :+= RodBind("dbsnp", "VCF", dbSNP)
    this.rodBind :+= RodBind("indels", "VCF", qscript.indels)
    this.useOnlyKnownIndels = knownsOnly
    this.doNotUseSW = useSW
    this.compress = Some(0)
    this.U = Some(org.broadinstitute.sting.gatk.arguments.ValidationExclusion.TYPE.NO_READ_ORDER_VERIFICATION)  // todo -- update this with the last consensus between Tim, Matt and Eric. This is ugly!
    this.jobName = queueLogDir + outBam + ".clean"
  }

  case class dedup (inBam: File, outBam: File, metricsFile: File) extends PicardBamJarFunction {
    @Input(doc="fixed bam") var clean = inBam
    @Output(doc="deduped bam") var deduped = outBam
    @Output(doc="deduped bam index") var dedupedIndex = new File(outBam + ".bai")
    @Output(doc="metrics file") var metrics = metricsFile
    override def inputBams = List(clean)
    override def outputBam = deduped
    override def commandLine = super.commandLine + " M=" + metricsFile + " CREATE_INDEX=true"
    sortOrder = null
    this.memoryLimit = Some(6)
    this.jarFile = qscript.dedupJar
    this.isIntermediate = true
    this.jobName = queueLogDir + outBam + ".dedup"
  }

  case class cov (inBam: File, outRecalFile: File) extends CountCovariates with CommandLineGATKArgs {
    this.rodBind :+= RodBind("dbsnp", "VCF", dbSNP)
    this.covariate ++= List("ReadGroupCovariate", "QualityScoreCovariate", "CycleCovariate", "DinucCovariate")
    this.input_file :+= inBam
    this.recal_file = outRecalFile
    this.jobName = queueLogDir + outRecalFile + ".covariates"
  }

  case class recal (inBam: File, inRecalFile: File, outBam: File) extends TableRecalibration with CommandLineGATKArgs {
    @Output(doc="recalibrated bam index") var recalIndex = new File(outBam + ".bai")
    this.input_file :+= inBam
    this.recal_file = inRecalFile
    this.baq = Some(org.broadinstitute.sting.utils.baq.BAQ.CalculationMode.CALCULATE_AS_NECESSARY)
    this.out = outBam
    if (!qscript.intervalString.isEmpty()) this.intervalsString ++= List(qscript.intervalString)
    else if (qscript.intervals != null) this.intervals :+= qscript.intervals
    this.U = Some(org.broadinstitute.sting.gatk.arguments.ValidationExclusion.TYPE.NO_READ_ORDER_VERIFICATION)  // todo -- update this with the last consensus between Tim, Matt and Eric. This is ugly!
    this.index_output_bam_on_the_fly = Some(true)
    this.jobName = queueLogDir + outBam + ".recalibration"
  }

  case class analyzeCovariates (inRecalFile: File, outPath: File) extends AnalyzeCovariates {
    this.jarFile = qscript.ACJar
    this.resources = qscript.R
    this.recal_file = inRecalFile
    this.output_dir = outPath.toString
    this.jobName = queueLogDir + inRecalFile + ".analyze_covariates"
  }

  case class writeList(inBams: List[File], outBamList: File) extends ListWriterFunction {
    this.inputFiles = inBams
    this.listFile = outBamList
    this.jobName = queueLogDir + outBamList + ".bamList"
  }
}

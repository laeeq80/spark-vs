package se.uu.farmbio.vs

import se.uu.farmbio.sg.SGUtils

import java.io.PrintWriter
import java.io.StringWriter
import java.io.ByteArrayInputStream
import java.nio.file.Paths
import java.nio.charset.Charset

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.io.Source

import org.apache.spark.SparkFiles
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.SparkContext._

import org.openscience.cdk.io.SDFWriter
import org.openscience.cdk.io.MDLV2000Reader
import org.openscience.cdk.interfaces.IAtomContainer
import org.openscience.cdk.tools.manipulator.ChemFileManipulator
import org.openscience.cdk.silent.ChemFile

trait ConformerTransforms {
  val DOCKING_CPP_URL = "http://pele.farmbio.uu.se/spark-vs/dockingstd"
  def dock(receptorPath: String, method: Int, resolution: Int): SBVSPipeline with PoseTransforms
  def repartition: SBVSPipeline with ConformerTransforms
  def generateSignatures(): SBVSPipeline with ConformersWithSignsTransforms
}

object ConformerPipeline {

  //The Spark built-in pipe splits molecules line by line, we need a custom one
  private[vs] def pipeString(str: String, command: List[String]) = {

    //Start executable
    val pb = new ProcessBuilder(command.asJava)
    val proc = pb.start
    // Start a thread to print the process's stderr to ours
    new Thread("stderr reader") {
      override def run() {
        for (line <- Source.fromInputStream(proc.getErrorStream).getLines) {
          System.err.println(line)
        }
      }
    }.start
    // Start a thread to feed the process input 
    new Thread("stdin writer") {
      override def run() {
        val out = new PrintWriter(proc.getOutputStream)
        out.println(str)
        out.close()
      }
    }.start
    //Return results as a single string
    Source.fromInputStream(proc.getInputStream).mkString

  }

  private def sdfStringToIAtomContainer(sdfRecord: String) = {

    //Get SDF as input stream
    val sdfByteArray = sdfRecord
      .getBytes(Charset.forName("UTF-8"))
    val sdfIS = new ByteArrayInputStream(sdfByteArray)

    //Parse SDF
    val reader = new MDLV2000Reader(sdfIS)
    val chemFile = reader.read(new ChemFile)
    val mols = ChemFileManipulator.getAllAtomContainers(chemFile)

    //mols is a Java list :-(
    val it = mols.iterator
    var res = Seq[(IAtomContainer)]()
    while (it.hasNext()) {
      //for each molecule in the record compute the signature
      val mol = it.next
      res = res ++ Seq(mol)
    }

    res //return the molecule
  }

  private def writeSignature(sdfRecord: String, signature: String) = {

    //Get SDF as input stream
    val sdfByteArray = sdfRecord
      .getBytes(Charset.forName("UTF-8"))
    val sdfIS = new ByteArrayInputStream(sdfByteArray)

    //Parse SDF
    val reader = new MDLV2000Reader(sdfIS)
    val chemFile = reader.read(new ChemFile)
    val mols = ChemFileManipulator.getAllAtomContainers(chemFile)

    //mols is a Java list :-(
    val strWriter = new StringWriter()
    val writer = new SDFWriter(strWriter)
    val it = mols.iterator
    while (it.hasNext()) {
      val mol = it.next
      mol.setProperty("Signature", signature)
      mol.removeProperty("cdk:Remark")
      writer.write(mol)
    }
    writer.close
    reader.close
    strWriter.toString() //return the molecule  

  }

}

private[vs] class ConformerPipeline(override val rdd: RDD[String])
    extends SBVSPipeline(rdd) with ConformerTransforms {

  override def dock(receptorPath: String, method: Int, resolution: Int) = {

    //Use local CPP if DOCKING_CPP is set
    val dockingstdPath = if (System.getenv("DOCKING_CPP") != null) {
      logInfo("using local cpp: " + System.getenv("DOCKING_CPP"))
      System.getenv("DOCKING_CPP")
    } else {
      logInfo("using remote cpp: " + DOCKING_CPP_URL)
      DOCKING_CPP_URL
    }

    sc.addFile(dockingstdPath)
    sc.addFile(receptorPath)
    val receptorFileName = Paths.get(receptorPath).getFileName.toString
    val dockingstdFileName = Paths.get(dockingstdPath).getFileName.toString
    val pipedRDD = rdd.map { sdf =>
      ConformerPipeline.pipeString(sdf,
        List(SparkFiles.get(dockingstdFileName),
          method.toString(),
          resolution.toString(),
          SparkFiles.get(receptorFileName)))
    }

    val res = pipedRDD.flatMap(SBVSPipeline.splitSDFmolecules)
    new PosePipeline(res)
  }

  override def generateSignatures = {
    //Split molecules, so there is only one molecule per RDD record
    val splitRDD = rdd.flatMap(SBVSPipeline.splitSDFmolecules)
    //Convert to IAtomContainer, fake labels are added
    val molsWithFakeLabels = splitRDD.flatMap {
      case (sdfmol) =>
        ConformerPipeline.sdfStringToIAtomContainer(sdfmol)
          .map {
            case (mol) =>
              //Make sg library happy
              (sdfmol, 0.0, mol) // sdfmol is a carry, 0.0 is fake label and mol is the IAtomContainer
          }
    }
    //Convert to labeled point 
    val (lps, _) = SGUtils.atoms2LP_UpdateSignMapCarryData(molsWithFakeLabels, null, 1, 3)
    //Throw away the labels and only keep the features 
    val molAndSparseVector = lps.map {
      case (mol, lp) => (mol, lp.features.toSparse.toString())
    }
    //Write Signature in the SDF String
    val res = molAndSparseVector.map {
      case (mol, sign) => ConformerPipeline.writeSignature(mol, sign)
    }
    new ConformersWithSignsPipeline(res)
  }

  override def repartition() = {
    val res = rdd.repartition(defaultParallelism)
    new ConformerPipeline(res)
  }

}

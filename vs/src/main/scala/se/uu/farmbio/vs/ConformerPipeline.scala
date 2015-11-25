package se.uu.farmbio.vs

import java.io.InputStream

import org.apache.commons.lang.NotImplementedException
import org.apache.spark.rdd.RDD

trait ConformerTransforms {

  def dock(receptor: InputStream, method: Int, resolution: Int): SBVSPipeline with PoseTransforms
  def repartition: SBVSPipeline with ConformerTransforms

}

class ConformerPipeline[vs](override val rdd: RDD[String])
  extends SBVSPipeline(rdd) with ConformerTransforms {
    
  override def dock(receptor: InputStream, method: Int, resolution: Int) = {
//    val receptorBytes = IOUtils.toByteArray(receptor)
//    val bcastReceptor = sc.broadcast(receptorBytes)
//    val res = rdd.flatMap(OEChemLambdas.oeDocking(bcastReceptor, method, resolution, oeErrorLevel))
//    new PosePipeline(res)
    val cppExePath = "/home/laeeq/Desktop/spark-vs/docking-cpp/dockingstd"
    val pipedRDD = rdd.pipe(cppExePath)
    val res = pipedRDD.collect()
    val string = res.mkString("\n")
    val res2 = SBVSPipeline.splitSDFmolecules(string)
    val cppRDD = sc.makeRDD(res2)    
    new PosePipeline(cppRDD)
    
      //throw new NotImplementedException("Needs to be reimplemented due to memory issue")
  }

  override def repartition() = {
    val res = rdd.repartition(defaultParallelism)
    new ConformerPipeline(res)
  }

}
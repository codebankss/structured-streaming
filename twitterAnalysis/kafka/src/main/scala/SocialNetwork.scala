import org.graphframes._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object SocialNetwork {
  def main(args: Array[String]): Unit = {
    if (args.length != 2) {
      println("Usage: SocialNetwork <path of input file> <path of output directory>")
    }

    val spark = SparkSession
      .builder()
      .appName("SocialNetwork")
      .master("local[*]")
      .getOrCreate()

    val sc = spark.sparkContext

    val filtered = sc.textFile(args(0)).filter(!_.startsWith("#")).cache()

    val edgesDF = spark.createDataFrame(filtered.map(x => {val e = x.split("\t")
      (e(0), e(1))}))

    val edges = edgesDF.toDF("src", "dst")

    val vertDF = spark.createDataFrame(filtered.flatMap(x => x.split("\t")).map( x => (x,1)))
    val vertices = vertDF.toDF("id", "c").select("id").distinct()

    val graph = GraphFrame(vertices, edges).cache()

    //Outdegree
    val outDeg = graph.outDegrees.orderBy(desc("outDegree")).limit(5)
    outDeg.write.option("header", true).csv(args(1)+"/outDegree")

    //InDegree
    val inDeg = graph.inDegrees.orderBy(desc("inDegree")).limit(5)
    inDeg.write.option("header", true).csv(args(1)+"/inDegree")

    //PageRank
    val pageRank = graph.pageRank.maxIter(10).resetProbability(0.1).run()
    val pr = pageRank.vertices.orderBy(desc("pageRank")).limit(5)
    pr.write.option("header", true).csv(args(1)+"/pageRank")

    //Connected Components
    /*var i = args(0).lastIndexOf("/")
    if(i == -1){
      i = args(0).lastIndexOf("\\")
    }
    sc.setCheckpointDir(args(0).substring(0, i))*/
    sc.setCheckpointDir("/tmp/checkpoints")
    val conComponents = graph.connectedComponents.run()
    val cc = conComponents.groupBy("component").count().orderBy(desc("count")).limit(5)
    cc.write.option("header", true).csv(args(1)+"/connectedComponents")

    //Triangle Count
    val trCount = graph.triangleCount.run()
    val triangleCount = trCount.orderBy(desc("count")).limit(5)
    triangleCount.write.option("header", true).csv(args(1)+"/triangleCount")
  }
}
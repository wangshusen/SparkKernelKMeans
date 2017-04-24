// spark-core
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.rdd._
// spark-sql
import org.apache.spark.sql.SparkSession
// spark-mllib
import org.apache.spark.mllib.clustering.{KMeans, KMeansModel}
import org.apache.spark.mllib.linalg.{Vector, Vectors, Matrix, Matrices}
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.mllib.linalg.SingularValueDecomposition
// breeze
import breeze.linalg._
import breeze.numerics._
// others
import scala.math
import java.io._


object KernelKMeansExample {
    def main(args: Array[String]) {
        // Parse parameters from command line arguments
        val CLUSTER_NUM: Int = if(args.length > 0) args(0).toInt else 10
        val TARGET_DIM: Int = if(args.length > 1) args(1).toInt else CLUSTER_NUM * 2
        val SKETCH_SIZE: Int = if(args.length > 2) args(2).toInt else TARGET_DIM * 10
        val SIGMA = if(args.length > 3) args(3).toDouble else 10.0
        
        println("####################################")
        println("cluster number = " + CLUSTER_NUM.toString)
        println("target dimension = " + TARGET_DIM.toString)
        println("sketch size = " + SKETCH_SIZE.toString)
        println("sigma = " + SIGMA.toString)
        println("####################################")
        
        // Path of input and output files
        val DATA_FILE = System.getenv("DATA_FILE")
        val OUTPUT_FILE = System.getenv("OUTPUT_FILE")
        val OUTPUT_FILE_LABEL = OUTPUT_FILE + ".txt"
        val OUTPUT_FILE_TIME = OUTPUT_FILE + ".time.txt"
        
        // Launch Spark
        val spark = (SparkSession
                      .builder()
                      .appName("Spark SQL basic example")
                      .config("spark.some.config.option", "some-value")
                      .getOrCreate())
        val sc = spark.sparkContext
        
        // Loads data
        val df = spark.read.format("libsvm").load(DATA_FILE)
        
        // Parse the data to get labels and features
        val label_vector_rdd: RDD[(Int, Array[Double])] = df.rdd
                .map(pair => (pair(0).toString.toFloat.toInt, Vectors.parse(pair(1).toString).toArray))
                .persist()
        
        // Perform kernel k-means with Nystrom approximation
        val result: (Array[String], Array[String]) = kernel_kmeans(sc, label_vector_rdd, CLUSTER_NUM, TARGET_DIM, SKETCH_SIZE, SIGMA)
        
        // Write (true label, predicted label) pairs to file OUTPUT_FILE
        val label_str = (result._1 mkString " ").trim
        val writer1 = new PrintWriter(new File(OUTPUT_FILE_LABEL))
        writer1.write(label_str)
        writer1.close()
        
        // Write elapsed time (nano seconds) to file OUTPUT_FILE_TILE
        val time_str = (result._2 mkString " ").trim
        val writer2 = new PrintWriter(new File(OUTPUT_FILE_TIME))
        writer2.write(time_str)
        writer2.close()
        
        spark.stop()
    }
    
    /**
     * Kernel k-means clustering with Nystrom approximation.
     *
     * Input
     *  sc: SparkContext
     *  label_vector_rdd: RDD of labels and raw input feature vectors
     *  k: cluster number
     *  s: target dimension of extracted feature vectors
     *  c: sketch size (k < s < c/2)
     *  sigma: kernel width parameter
     *
     * Output
     *  labels: Array of (true label, predicted label) pairs
     *  time: Array of the elapsed time of Nystrom, PCA, and k-means, respectively
     */
    def kernel_kmeans(sc: SparkContext, label_vector_rdd: RDD[(Int, Array[Double])], k: Int, s: Int, c: Int, sigma: Double): (Array[String], Array[String]) = {
        // max number of iterations (can be tuned)
        val MAX_ITER: Int = 100
        
        // Record the elapsed time
        val time = new Array[String](3)
        label_vector_rdd.count

        // Extract features by the Nystrom method
        val t0 = System.nanoTime()
        val nystrom_rdd: RDD[(Int, Vector)] = nystrom(sc, label_vector_rdd, c, sigma).persist()
        nystrom_rdd.count
        val t1 = System.nanoTime()
        time(0) = ((t1 - t0) * 1.0E-9).toString
        println("####################################")
        println("Nystrom method costs  " + time(0) + "  seconds.")
        println("####################################")
        
        // Extract s principal components from the Nystrom features
        // The V matrix stored in a local dense matrix
        val mat: RowMatrix = new RowMatrix(nystrom_rdd.map(pair => pair._2))
        val svd: SingularValueDecomposition[RowMatrix, Matrix] = mat.computeSVD(s, computeU = false)
        val v_mat: Matrix = svd.V.transpose
        val broadcast_v_mat = sc.broadcast(v_mat)
        val nystrom_pca_rdd: RDD[(Int, Vector)] = nystrom_rdd
                .map(pair => (pair._1, broadcast_v_mat.value.multiply(pair._2)))
                .map(pair => (pair._1, Vectors.dense(pair._2.toArray)))
                .persist()
        nystrom_pca_rdd.count
        //broadcast_v_mat.destroy()
        val t2 = System.nanoTime()
        time(1) = ((t2 - t1) * 1.0E-9).toString
        println("####################################")
        println("PCA costs  " + time(1) + "  seconds.")
        println("####################################")

        // K-means clustering over the extracted features
        val feature_rdd: RDD[Vector] = nystrom_pca_rdd.map(pair => pair._2)
        val clusters = KMeans.train(feature_rdd, k, MAX_ITER)
        val t3 = System.nanoTime()
        time(2) = ((t3 - t2) * 1.0E-9).toString
        println("####################################")
        println("K-means clustering costs  " + time(2) + "  seconds.")
        println("####################################")
        
        // Predict labels
        val broadcast_clusters = sc.broadcast(clusters)
        val labels: Array[String] = nystrom_pca_rdd
                .map(pair => (pair._1, broadcast_clusters.value.predict(pair._2)))
                .map(pair => pair._1.toString + " " + pair._2.toString)
                .collect()
        
        (labels, time)
    }
    
    /**
     * The Nystrom method for approximating the RBF kernel matrix.
     * Let K be n-by-n kernel matrix. The Nystrom method approximates 
     * K by C * W^{-1}_l * C^T, where we set l = c/2.
     * The Nystrom feature vectors are the rows of C * W^{-1/2}_l.
     *
     * Input
     *  sc: SparkContext
     *  label_vector_rdd: RDD of labels and raw input feature vectors
     *  c: sketch size (k < s < c/2)
     *  sigma: kernel width parameter
     *
     * Output
     *  RDD of (lable, Nystrom feature vector) pairs
     *  the dimension of Nystrom feature vector is (c/2)
     */
    def nystrom(sc: SparkContext, label_vector_rdd: RDD[(Int, Array[Double])], c: Int, sigma: Double): RDD[(Int, Vector)] = {
        val n = label_vector_rdd.count
        
        // Randomly sample about c points from the dataset
        val frac = c.toDouble / n.toDouble
        val data_samples = label_vector_rdd.sample(false, frac).map(pair => pair._2).collect
        val broadcast_samples = sc.broadcast(data_samples)
        
        // Compute the C matrix of the Nystrom method
        val rbf_fun = (x1: Array[Double], x2: Array[Array[Double]]) => rbf(x1, x2, sigma)
        val broadcast_rbf = sc.broadcast(rbf_fun)
        val c_mat_rdd = label_vector_rdd
                .map(pair => (pair._1, broadcast_rbf.value(pair._2, broadcast_samples.value)))
                .map(pair => (pair._1, new DenseVector(pair._2)))
        
        // Compute the W matrix of the Nystrom method
        // decompose W as: U * U^T \approx W^{-1}
        val u_mat = nystrom_w_mat(data_samples, sigma)
        val broadcast_u_mat = sc.broadcast(u_mat)          
        
        // Compute the features extracted by Nystrom 
        // feature matrix is C * W^{-1/2}_{c/2}
        val nystrom_rdd = c_mat_rdd
                .map(pair => (pair._1, pair._2.t * broadcast_u_mat.value))
                .map(pair => (pair._1, Vectors.dense(pair._2.t.toArray)))
        
        nystrom_rdd
    }
    
    /**
     * Locally compute the W matrix of Nystrom and its factorization W_{l}^{-1} = U * U^T.
     */
    def nystrom_w_mat(data_samples: Array[Array[Double]], sigma: Double): DenseMatrix[Double] = {
        val c = data_samples.length
        val l = math.ceil(c * 0.5).toInt
        
        // Compute the W matrix of Nystrom method
        val w_mat = DenseMatrix.zeros[Double](c, c)	
        for (j <- 0 until c) {
            for (i <- 0 until c) {
                w_mat(i, j) = rbf(data_samples(i), data_samples(j), sigma)
            }
        }
        
        // Compute matrix U such that U * U^T = W_{l}^{-1}
        val usv = svd.reduced(w_mat)
        val u_mat = usv.U(::, 0 until l)
        val s_arr = usv.S(0 until l).toArray.map(s => 1 / math.sqrt(s))
        for (j <- 0 until l) {
            u_mat(::, j) :*= s_arr(j)
        }
        
        u_mat
    }
    
    /**
     * Compute the RBF kernel function of two vectors.
     */
    def rbf(x1: Array[Double], x2: Array[Double], sigma: Double): Double = {
        // squared l2 distance between x1 and x2
        val dist = x1.zip(x2).map(pair => pair._1 - pair._2).map(x => x*x).sum
        // RBF kernel function is exp( - ||x1-x2||^2 / 2 / sigma^2 )
        math.exp(- dist / (2 * sigma * sigma))
    }
    
    /**
     * Compute the RBF kernel functions of a vector and a collection of vectors.
     */
    def rbf(x1: Array[Double], x2: Array[Array[Double]], sigma: Double): Array[Double] = {
        val n = x2.length
        val kernel_arr = new Array[Double](n)
        val sigma_sq = 2 * sigma * sigma
        var dist = 0.0
        for (i <- 0 until n) {
            // squared l2 distance between x1 and x2(i)
            dist = x1.zip(x2(i)).map(pair => pair._1 - pair._2).map(x => x*x).sum
            // RBF kernel function
            kernel_arr(i) = math.exp(- dist / sigma_sq)
        }
        kernel_arr
    }
    
}
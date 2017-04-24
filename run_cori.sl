#!/bin/bash
#SBATCH -p regular
#SBATCH -N 16
#SBATCH -C haswell
#SBATCH -t 00:10:00
#SBATCH -J wss_kernel_kmeans
#SBATCH -L SCRATCH
#SBATCH -e mysparkjob_%j.err
#SBATCH -o mysparkjob_%j.out

PROJ_HOME="$SCRATCH/SparkKernelKMeans"
JAR_FILE="$PROJ_HOME/target/scala-2.11/kmeans_2.11-1.0.jar"

export DATA_FILE="$PROJ_HOME/data/mnist"
export OUTPUT_FILE="$PROJ_HOME/result/kernel_kmeans_result"

module load python/3.5-anaconda
module load spark
start-all.sh

spark-submit \
    --class "KernelKMeansExample" \
    -n 256 \
    $JAR_FILE 10 20 100 8.0
    
    
stop-all.sh

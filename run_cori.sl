#!/bin/bash
#SBATCH -p regular
#SBATCH -N 64
#SBATCH -C haswell
#SBATCH -t 00:20:00
#SBATCH -J wss_kernel_kmeans
#SBATCH -L SCRATCH
#SBATCH -e mysparkjob_%j.err
#SBATCH -o mysparkjob_%j.out

export PROJ_HOME="$SCRATCH/SparkKernelKMeans"
export JAR_FILE="$PROJ_HOME/target/scala-2.11/kmeans_2.11-1.0.jar"

export DATA_FILE="$PROJ_HOME/data/mnist8m"
export OUTPUT_FILE="$PROJ_HOME/result/kernel_kmeans_result_N64"
module load python/3.5-anaconda
module load spark
ulimit -s unlimited
start-all.sh

spark-submit \
    --class "KernelKMeansExample" \
    $JAR_FILE 10 20 400 1840.0 

python $PROJ_HOME/result/kmeans_nmi.py -f $OUTPUT_FILE".txt"

stop-all.sh

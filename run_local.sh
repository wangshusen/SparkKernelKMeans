#!/usr/bin/env bash

PROJ_HOME="$HOME/Code/SparkKernelKMeans"
SPARK_HOME="$HOME/Software/spark-2.1.0"
JAR_FILE="$PROJ_HOME/target/scala-2.11/kmeans_2.11-1.0.jar"
MASTER="local[8]"

export DATA_FILE="$PROJ_HOME/data/mnist"
export OUTPUT_FILE="$PROJ_HOME/result/kernel_kmeans_result"

$SPARK_HOME/bin/spark-submit \
    --class "KernelKMeansExample" \
    --driver-memory 2G \
    --num-executors 5 \
    --executor-cores 1 \
    --executor-memory 700M \
    --master $MASTER \
    $JAR_FILE 10 20 100 10.0 \
    > result.out
  
python $PROJ_HOME/result/kmeans_nmi.py -f $OUTPUT_FILE".txt" \
    >> result.out

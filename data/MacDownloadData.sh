#!/usr/bin/env bash

curl "http://www.csie.ntu.edu.tw/~cjlin/libsvmtools/datasets/multiclass/mnist.scale.bz2" -o "mnist.bz2"
bzip2 -d mnist.bz2


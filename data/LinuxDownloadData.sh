#!/usr/bin/env bash

wget http://www.csie.ntu.edu.tw/~cjlin/libsvmtools/datasets/multiclass/mnist.scale.bz2
bzip2 -d mnist.scale.bz2
mv mnist.scale mnist

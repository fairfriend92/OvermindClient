//
// Created by rodolfo on 08/12/16.
//

#ifndef MYAPPLICATION_NATIVE_METHOD_H_H
#define MYAPPLICATION_NATIVE_METHOD_H_H

#endif //MYAPPLICATION_NATIVE_METHOD_H_H

// Constants
#define SAMPLING_RATE 0.5
#define EXC_SYNAPSE_TIME_SCALE 1
#define INH_SYNAPSE_TIME_SCALE 3
#define ABSOLUTE_REFRACTORY_PERIOD 2

// Abbreviations
#define potentialVar obj->neuronalDynVar[workId * 3]
#define recoveryVar obj->neuronalDynVar[workId * 3 + 1]
#define aPar simulationParameters[0]
#define bPar simulationParameters[1]
#define cPar simulationParameters[2]
#define dPar simulationParameters[3]
#define IPar simulationParameters[4]

#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include "CL/cl.h"
#include "common.h"

struct OpenCLObject {
    // OpenCL implementation
    cl_context context = 0;
    cl_command_queue commandQueue = 0;
    cl_program program = 0;
    cl_device_id device = 0;
    cl_kernel kernel = 0;
    cl_int errorNumber = 0;
    int numberOfMemoryObjects = 4;
    cl_mem memoryObjects[4] = {0, 0, 0, 0};
    cl_uint floatVectorWidth;
    size_t maxWorkGroupSize;

    // Pointers to the memory buffers
    cl_float *synapseCoeff;
    cl_float *synapseWeights;
    cl_char *synapseInput;
    cl_int *current;
    float *neuronalDynVar;
};


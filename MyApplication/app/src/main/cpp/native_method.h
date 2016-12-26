//
// Created by rodolfo on 08/12/16.
//

#ifndef MYAPPLICATION_NATIVE_METHOD_H_H
#define MYAPPLICATION_NATIVE_METHOD_H_H

#endif //MYAPPLICATION_NATIVE_METHOD_H_H

#define SAMPLING_RATE 0.1
#define SYNAPSE_FILTER_ORDER 64
#define EXC_SYNAPSE_TIME_SCALE 1
#define INH_SYNAPSE_TIME_SCALE 3
#define NUMBER_OF_EXC_SYNAPSES 4
#define NUMBER_OF_INH_SYNAPSES 4
#define NUMBER_OF_NEURONS 8
#define ABSOLUTE_REFRACTORY_PERIOD 2

#define SHIFT_FACTOR 15

#include <android/log.h>
#include <jni.h>
#include <math.h>
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
    cl_uint intVectorWidth = 0;

    // Pointers to the memory buffers
    cl_uint *excSynapseCoeff;
    cl_uint *inhSynapseCoeff;
    cl_uchar *synapseInput;
};


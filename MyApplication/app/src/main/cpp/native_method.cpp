//
// Created by rodolfo on 27/11/16.
//

#include "native_method.h"

// Maximum number of multiplications needed to compute the current response of a synapse
int maxNumberMultiplications = (int) (ABSOLUTE_REFRACTORY_PERIOD / SAMPLING_RATE ) + 1;
// Size of the buffers needed to store data
size_t excSynapseCoeffBufferSize = SYNAPSE_FILTER_ORDER * sizeof(cl_float);
size_t excSynapseInputBufferSize = maxNumberMultiplications * NUMBER_OF_EXC_SYNAPSES * sizeof(cl_int);
size_t synapseOutputBufferSize = NUMBER_OF_EXC_SYNAPSES * sizeof(cl_float);

extern "C" jlong Java_com_example_myapplication_Simulation_initializeOpenCL (
        JNIEnv *env, jobject thiz, jstring jMacKernel) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)malloc(sizeof(struct OpenCLObject));

    const char *kernelString = env->GetStringUTFChars(jMacKernel, JNI_FALSE);

    if (!createContext(&obj->context))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL context");
        return -1;
    }

    if (!createCommandQueue(obj->context, &obj->commandQueue, &obj->device))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL command queue");
        return -1;
    }

    if (!createProgram(obj->context, obj->device, kernelString, &obj->program))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL program");
        return -1;
    }

    // Query the device to find out its preferred integer vector width
    clGetDeviceInfo(obj->device, CL_DEVICE_PREFERRED_VECTOR_WIDTH_INT, sizeof(cl_uint), &obj->integerVectorWidth, NULL);
    LOGD("Preferred vector width for integers: %d ", obj->integerVectorWidth);

    obj->kernel = clCreateKernel(obj->program, "mac_kernel_vec4", &obj->errorNumber);
    if (!checkSuccess(obj->errorNumber))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL kernel");
        return -1;
    }

    // Release the string containing the kernel since it has been passed already to createProgram
    env->ReleaseStringUTFChars(jMacKernel, kernelString);

    bool createMemoryObjectsSuccess = true;
    obj->numberOfMemoryObjects= 3;

    // Ask the OpenCL implementation to allocate buffers to pass data to and from the kernels
    obj->memoryObjects[0] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, excSynapseCoeffBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[1] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, excSynapseInputBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[2] = clCreateBuffer(obj->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseOutputBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!createMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL buffer");
        return -1;
    }

    // Map the memory buffers created by the OpenCL implementation so we can access them on the CPU
    bool mapMemoryObjectsSuccess = true;

    obj->excSynapseCoeff = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[0], CL_TRUE, CL_MAP_WRITE, 0, excSynapseCoeffBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->excSynapseInput = (cl_int*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_WRITE, 0, excSynapseInputBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
        return -1;
    }

    // Initialize the coefficients array by sampling the exponential kernel of the synapse filter
    // TODO: initialize coefficients array using OpenCL kernel
    float tExc = (float) (SAMPLING_RATE / EXC_SYNAPSE_TIME_SCALE);
    for (int index = 0; index < SYNAPSE_FILTER_ORDER; index++)
    {
        obj->excSynapseCoeff[index] = (index * tExc) * expf( - index * tExc);
    }

    return (long) obj;
}

extern "C" jlong Java_com_example_myapplication_Simulation_simulateNetwork(
        JNIEnv *env, jobject thiz, jbooleanArray jPresynapticSpikes, jlong jOpenCLObject) {

    jboolean *presynapticSpikes = env->GetBooleanArrayElements(jPresynapticSpikes, 0);

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)jOpenCLObject;

    // Initialization for classic kernel (uncomment code in kernel too)
    /*
    for (int index = 0; index < (SYNAPSE_FILTER_ORDER - 1) * NUMBER_OF_EXC_SYNAPSES; index++) {
        obj->excSynapseInput[index + NUMBER_OF_EXC_SYNAPSES] = obj->excSynapseInput[index];
        //if(obj->excSynapseInput[index]) { LOGD("%d", index); } // uncomment for testing
    }
    for (int index = 0; index < NUMBER_OF_EXC_SYNAPSES; index++) {
        obj->excSynapseInput[index] = presynapticSpikes[index];
        //if(obj->excSynapseInput[index]) { LOGD("%d", index); } // uncomment for testing
    }
     */

    // Initialize the input of the kernel
    for (int indexI = 0; indexI < NUMBER_OF_EXC_SYNAPSES; indexI++)
    {
        // Advance the inputs in the filter pipeline if needed
        if (presynapticSpikes[indexI] || obj->excSynapseInput[indexI * maxNumberMultiplications - 1] == SYNAPSE_FILTER_ORDER - 1)
        {
            for (int indexJ = 1; indexJ < maxNumberMultiplications; indexJ++)
            {
                obj->excSynapseInput[indexJ + indexI * maxNumberMultiplications] =
                obj->excSynapseInput[indexJ + indexI * maxNumberMultiplications - 1];
            }
        }
    }

    // Tell the kernels which data to use before they are scheduled
    bool setKernelArgumentSuccess = true;
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 0, sizeof(cl_mem), &obj->memoryObjects[0]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 1, sizeof(cl_mem), &obj->memoryObjects[1]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 2, sizeof(cl_mem), &obj->memoryObjects[2]));

    if (!setKernelArgumentSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to set OpenCL kernel arguments");
        return -1;
    }

    return (long) obj;
}

extern "C" int Java_com_example_myapplication_Simulation_closeOpenCL(
        JNIEnv *env, jobject thiz,  jlong jOpenCLObject) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *) jOpenCLObject;

    // Un-map the memory objects as we have finished using them from the CPU side
    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[0], obj->excSynapseCoeff, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->excSynapseInput, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects))
    {
        LOGE("Failed to clean-up OpenCL");
        return -1;
    };

    free(obj);
    return 1;
}

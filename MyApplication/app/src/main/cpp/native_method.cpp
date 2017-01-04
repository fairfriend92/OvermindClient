//
// Created by rodolfo on 27/11/16.
//

#include "native_method.h"

// Maximum number of multiplications needed to compute the current response of a synapse
 int maxNumberMultiplications = (int) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD + 1);
// Size of the buffers needed to store data
size_t synapseCoeffBufferSize = SYNAPSE_FILTER_ORDER * 4 * sizeof(cl_float);
size_t synapseWeightsBufferSize = (NUMBER_OF_EXC_SYNAPSES + NUMBER_OF_INH_SYNAPSES) * sizeof(cl_half);
size_t synapseInputBufferSize = maxNumberMultiplications * (NUMBER_OF_EXC_SYNAPSES + NUMBER_OF_INH_SYNAPSES) * sizeof(cl_uchar);
size_t localDataBufferSize = NUMBER_OF_NEURONS * 2 * sizeof(cl_int);
size_t neuronalDynVarBufferSize = NUMBER_OF_NEURONS * 2 * sizeof(cl_float);
size_t actionPotentialsBufferSize = (NUMBER_OF_NEURONS / 8 + 1) * sizeof(cl_uchar);

extern "C" jlong Java_com_example_overmind_SimulationService_initializeOpenCL (
        JNIEnv *env, jobject thiz, jstring jSynapseKernel) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)malloc(sizeof(struct OpenCLObject));

    const char *kernelString = env->GetStringUTFChars(jSynapseKernel, JNI_FALSE);

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

    /**
     * Query the device to find various information. These are merely indicative since we must account for ther
     * complexity of the Kernel. For more information look at clGetKernelWorkGroupInfo in the simulateDynamics method.
     */
    cl_uint intVectorWidth;
    size_t maxWorkGroupSize;
    size_t maxWorkItems[3];
    cl_uint maxWorkItemDimension;
    cl_uint maxComputeUnits;
    char deviceName[256];

    clGetDeviceInfo(obj->device, CL_DEVICE_PREFERRED_VECTOR_WIDTH_INT, sizeof(cl_uint), &intVectorWidth, NULL);
    LOGD("Device info: Preferred vector width for integers: %d ", intVectorWidth);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeof(size_t), &maxWorkGroupSize, NULL);
    LOGD("Device info: Maximum work group size: %d ", maxWorkGroupSize);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS, sizeof(cl_uint), &maxWorkItemDimension, NULL);
    LOGD("Device info: Maximum work item dimension: %d", maxWorkItemDimension);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_WORK_ITEM_SIZES, sizeof(size_t[3]), maxWorkItems, NULL);
    LOGD("Device info: Maximum work item sizes for each dimension: %d, %d, %d", maxWorkItems[0], maxWorkItems[1], maxWorkItems[2]);

    clGetDeviceInfo(obj->device, CL_DEVICE_NAME, sizeof(char[256]), deviceName, NULL);
    LOGD("Device info: Device name: %s", deviceName);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_COMPUTE_UNITS, sizeof(cl_uint), &maxComputeUnits, NULL);
    LOGD("Device info: Maximum compute units: %d", maxComputeUnits);


    switch (intVectorWidth)
    {
        case 4:
            obj->kernel = clCreateKernel(obj->program, "synapse_vec4", &obj->errorNumber);
            if (!checkSuccess(obj->errorNumber))
            {
                cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
                LOGE("Failed to create OpenCL kernel");
                return -1;
            }
            break;
        default :
            LOGE("Failed to load kernel for device vector width");
    }

    // Release the string containing the kernel since it has been passed already to createProgram
    env->ReleaseStringUTFChars(jSynapseKernel, kernelString);

    bool createMemoryObjectsSuccess = true;
    obj->numberOfMemoryObjects= 3;

    // Ask the OpenCL implementation to allocate buffers to pass data to and from the kernels
    obj->memoryObjects[0] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY| CL_MEM_ALLOC_HOST_PTR, synapseCoeffBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[1] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseWeightsBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[2] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseInputBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[3] = clCreateBuffer(obj->context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, localDataBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[4] = clCreateBuffer(obj->context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, neuronalDynVarBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[5] = clCreateBuffer(obj->context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, actionPotentialsBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!createMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL buffer");
        return -1;
    }

    // Map the memory buffers created by the OpenCL implementation so we can access them on the CPU
    bool mapMemoryObjectsSuccess = true;

    obj->synapseCoeff = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[0], CL_TRUE, CL_MAP_WRITE, 0, synapseCoeffBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->synapseWeights = (cl_half*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_WRITE, 0, synapseWeightsBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->synapseInput = (cl_uchar*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[2], CL_TRUE, CL_MAP_WRITE, 0, synapseInputBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->localData = (cl_int*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[3], CL_TRUE, CL_MAP_WRITE, 0, localDataBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->neuronalDynVar = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[4], CL_TRUE, CL_MAP_WRITE, 0, neuronalDynVarBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->actionPotentials = (cl_uchar*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[5], CL_TRUE, CL_MAP_WRITE, 0, actionPotentialsBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
        return -1;
    }

    // Initialize the coefficients array by sampling the exponential kernel of the synapse filter
    float tExc = (float) (SAMPLING_RATE / EXC_SYNAPSE_TIME_SCALE);
    float tInh = (float) (SAMPLING_RATE / INH_SYNAPSE_TIME_SCALE);
    // Extend the loop beyond the synapse filter order to account for possible overflows of the filter input
    for (int index = 0; index < SYNAPSE_FILTER_ORDER * 4; index++)
    {
        obj->synapseCoeff[index] = index%2 == 0 ? index * tExc * expf( - index * tExc) : obj->synapseCoeff[index] = -index * tInh * expf( - index * tInh);
        LOGD("The synapse coefficients are: \tcoefficient %d \tvalue %f", index, (float) (obj->synapseCoeff[index]));
    }

    // For testing purposes we initialize the synapses weights here
    for (int index = 0; index < NUMBER_OF_EXC_SYNAPSES + NUMBER_OF_INH_SYNAPSES; index++)
    {
        obj->synapseWeights[index] = 1;
    }

    // The following could be local kernel variables as well, but that would call for a costly thread
    // syncrhonization. Instead we initialize them outside the kernel as global variables, since additional
    // memory access times are negligible due to the embedded nature of the device
    for (int index = 0; index < NUMBER_OF_NEURONS; index++)
    {
        obj->neuronalDynVar[2 * index] = (float)(-65.0);
        obj->neuronalDynVar[2 * index + 1] = (float)(-10.0);
        obj->localData[index] = obj->localData[index * 2] = 0;
    }

    return (long) obj;
}

extern "C" jlong Java_com_example_overmind_SimulationService_simulateDynamics(
        JNIEnv *env, jobject thiz, jcharArray jSynapseInput, jlong jOpenCLObject) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)jOpenCLObject;

    jchar *synapseInput = env->GetCharArrayElements(jSynapseInput, JNI_FALSE);

    for (int index = 0; index < maxNumberMultiplications * (NUMBER_OF_EXC_SYNAPSES + NUMBER_OF_INH_SYNAPSES); index++)
    {
        obj->synapseInput[index] = (cl_uchar)synapseInput[index];
    }

    env->ReleaseCharArrayElements(jSynapseInput, synapseInput, 0);

    // Tell the kernels which data to use before they are scheduled
    bool setKernelArgumentSuccess = true;
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 0, sizeof(synapseInputBufferSize), &obj->memoryObjects[0]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 1, sizeof(synapseWeightsBufferSize), &obj->memoryObjects[1]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 2, sizeof(synapseInputBufferSize), &obj->memoryObjects[2]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 3, sizeof(localDataBufferSize), &obj->memoryObjects[3]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 4, sizeof(neuronalDynVarBufferSize), &obj->memoryObjects[4]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 5, sizeof(actionPotentialsBufferSize), &obj->memoryObjects[5]));

    if (!setKernelArgumentSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to set OpenCL kernel arguments");
        return -1;
    }

    // Uncomment for more information on the kernel
    /*
    size_t kernelWorkGroupSize;
    cl_ulong localMemorySize;
    clGetKernelWorkGroupInfo(obj->kernel, obj->device, CL_KERNEL_WORK_GROUP_SIZE, sizeof(size_t), &kernelWorkGroupSize, NULL);
    LOGD("Kernel info: maximum work group size: %d", kernelWorkGroupSize);
    clGetKernelWorkGroupInfo(obj->kernel, obj->device, CL_KERNEL_LOCAL_MEM_SIZE, sizeof(cl_ulong), &localMemorySize, NULL);
    LOGD("Kernel info: local memory size in B: %lu", localMemorySize);
    */

    //  An event to associate with the kernel. Allows us to to retrieve profiling information later
    cl_event event = 0;

    // Number of kernel instances
    size_t globalWorksize[1] = {256};
    size_t localWorksize[1] = {256};
    // Enqueue kernel
    if (!checkSuccess(clEnqueueNDRangeKernel(obj->commandQueue, obj->kernel, 1, NULL, globalWorksize, localWorksize, 0, NULL, &event)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to enqueue OpenCL kernel");
        return -1;
    }

    // Wait for kernel execution completion
    if (!checkSuccess(clFinish(obj->commandQueue)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed waiting for kernel execution to finish");
        return -1;
    }

    // Print the profiling information for the event
    /*
    if(!printProfilingInfo(event))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to print profiling info");
        return -1;
    }
    */

    // Release the event object
    if (!checkSuccess(clReleaseEvent(event)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed releasing the event");
        return -1;
    }

    return (long) obj;
}

extern "C" jbyteArray Java_com_example_overmind_SimulationService_retrieveOutputSpikes(
        JNIEnv *env, jobject thiz, jlong jOpenCLObject) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *) jOpenCLObject;

    jbyteArray outputSpikes = env->NewByteArray(NUMBER_OF_NEURONS / 8 + 1);
    env->SetByteArrayRegion(outputSpikes, 0, NUMBER_OF_NEURONS / 8 + 1, reinterpret_cast<jbyte*>(obj->actionPotentials));

    return outputSpikes;
}

extern "C" int Java_com_example_overmind_SimulationService_closeOpenCL(
        JNIEnv *env, jobject thiz,  jlong jOpenCLObject) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *) jOpenCLObject;

    // Un-map the memory objects as we have finished using them from the CPU side
    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[0], obj->synapseCoeff, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->synapseWeights, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[2], obj->synapseInput, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[3], obj->localData, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[4], obj->neuronalDynVar, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
        return -1;
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[5], obj->actionPotentials, 0, NULL, NULL)))
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
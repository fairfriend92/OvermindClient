#include "native_method.h"

int SYNAPSE_FILTER_ORDER = 16;
short NUM_SYNAPSES = 1024;
short NUM_NEURONS = 1024;

// Maximum number of multiplications needed to compute the current response of a synapse
 int maxNumberMultiplications = 0;

// Size of the buffers needed to store data
size_t synapseCoeffBufferSize;
size_t synapseInputBufferSize;
size_t synapseWeightsBufferSize;
size_t currentBufferSize;
size_t presynFiringRatesBufferSize;
size_t postsynFiringRatesBufferSize;

// How many bytes are needed to represent every neuron's spike?
short dataBytes;

extern "C" jlong Java_com_example_overmind_SimulationService_initializeOpenCL (
        JNIEnv *env, jobject thiz, jstring jKernel, jshort jNumOfNeurons, jint jFilterOrder, jshort jNumOfSynapses) {
    // Create a new openCL object since the one created in the getNumOfSynapses function could not be returned
    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)malloc(sizeof(struct OpenCLObject)); // TODO: perhaps the pointer can be stored in this file like the buffer size variables?

    // Compute the size of the GPU buffers
    SYNAPSE_FILTER_ORDER = jFilterOrder;
    NUM_SYNAPSES = jNumOfSynapses;
    NUM_NEURONS = jNumOfNeurons;
    maxNumberMultiplications = (int) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD);
    synapseCoeffBufferSize = SYNAPSE_FILTER_ORDER * 2 * sizeof(cl_float);
    synapseInputBufferSize =  maxNumberMultiplications * NUM_SYNAPSES * sizeof(cl_uchar);
    synapseWeightsBufferSize = NUM_SYNAPSES * NUM_NEURONS * sizeof(cl_float);
    currentBufferSize = NUM_NEURONS * sizeof(cl_int);
    presynFiringRatesBufferSize = NUM_SYNAPSES * sizeof(cl_float);
    postsynFiringRatesBufferSize = NUM_NEURONS * sizeof(cl_float);

    // Compute the number of bytes needed to hold all the spikes
    dataBytes = (NUM_NEURONS % 8) == 0 ? (short)(NUM_NEURONS / 8) : (short)(NUM_NEURONS / 8 + 1);

    const char *kernelString = env->GetStringUTFChars(jKernel, JNI_FALSE);

    // The context must be created anew since the first time getNumOfSynapses didn't pass it back
    // to the java side of the application
    if (!createContext(&obj->context))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL context");
    }

    if (!createCommandQueue(obj->context, &obj->commandQueue, &obj->device))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL command queue");
    }

    if (!createProgram(obj->context, obj->device, kernelString, &obj->program))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL program");
    }

    /**
     * Query the device to find various information. These are merely indicative since we must account for the
     * complexity of the Kernel. For more information look at clGetKernelWorkGroupInfo in the simulateDynamics method.
     */

    size_t maxWorkItems[3];
    size_t maxWorkGroupSize;
    cl_uint maxWorkItemDimension;
    cl_uint maxComputeUnits;
    cl_bool compilerAvailable;
    cl_uint addressBits;
    char deviceName[256];

    clGetDeviceInfo(obj->device, CL_DEVICE_PREFERRED_VECTOR_WIDTH_FLOAT, sizeof(cl_uint), &obj->floatVectorWidth, NULL);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeof(size_t), &maxWorkGroupSize, NULL);

    obj->maxWorkGroupSize = maxWorkGroupSize < (NUM_SYNAPSES / obj->floatVectorWidth) ? maxWorkGroupSize : (NUM_SYNAPSES / obj->floatVectorWidth);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS, sizeof(cl_uint), &maxWorkItemDimension, NULL);
    LOGD("Device info: Maximum work item dimension: %d", maxWorkItemDimension);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_WORK_ITEM_SIZES, sizeof(size_t[3]), maxWorkItems, NULL);
    LOGD("Device info: Maximum work item sizes for each dimension: %d, %d, %d", (int)maxWorkItems[0], (int)maxWorkItems[1], (int)maxWorkItems[2]);

    clGetDeviceInfo(obj->device, CL_DEVICE_NAME, sizeof(char[256]), deviceName, NULL);
    LOGD("Device info: Device name: %s", deviceName);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_COMPUTE_UNITS, sizeof(cl_uint), &maxComputeUnits, NULL);
    LOGD("Device info: Maximum compute units: %d", maxComputeUnits);

    clGetDeviceInfo(obj->device, CL_DEVICE_COMPILER_AVAILABLE, sizeof(cl_bool), &compilerAvailable, NULL);
    LOGD("Device info: Device compiler available: %d", compilerAvailable);

    clGetDeviceInfo(obj->device, CL_DEVICE_ADDRESS_BITS, sizeof(cl_uint), &addressBits, NULL);
    LOGD("Device info: Device address bits: %d", addressBits);

    obj->kernel = clCreateKernel(obj->program, "simulate_dynamics", &obj->errorNumber);
    if (!checkSuccess(obj->errorNumber))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL kernel");
    }

    // Release the string containing the kernel since it has been passed already to createProgram
    env->ReleaseStringUTFChars(jKernel, kernelString);

    bool createMemoryObjectsSuccess = true;
    obj->numberOfMemoryObjects= 8;

    // Allocate memory from the host
    float *neuronalDynVar = new float[2 * NUM_NEURONS];
    obj->neuronalDynVar = neuronalDynVar;

    // Ask the OpenCL implementation to allocate buffers to pass data to and from the kernels
    obj->memoryObjects[0] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY| CL_MEM_ALLOC_HOST_PTR, synapseCoeffBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[1] = clCreateBuffer(obj->context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, synapseWeightsBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[2] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseInputBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[3] = clCreateBuffer(obj->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, currentBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[4] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, sizeof(cl_int), NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[5] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, presynFiringRatesBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[6] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, postsynFiringRatesBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[7] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseWeightsBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!createMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL buffer");
    }

    // Map the memory buffers created by the OpenCL implementation so we can access them on the CPU
    bool mapMemoryObjectsSuccess = true;

    obj->synapseCoeff = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[0], CL_TRUE, CL_MAP_WRITE, 0, synapseCoeffBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->synapseWeights = (cl_float *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_WRITE, 0, synapseWeightsBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->current = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[3], CL_TRUE, CL_MAP_WRITE, 0, currentBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->postsynFiringRates = (cl_float *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[6], CL_TRUE, CL_MAP_WRITE, 0, postsynFiringRatesBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->updateWeightsFlags = (cl_float *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[7], CL_TRUE, CL_MAP_WRITE, 0, synapseWeightsBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    // Initialize the coefficients array by sampling the exponential kernel of the synapse filter
    float tExc = (float) (SAMPLING_RATE / EXC_SYNAPSE_TIME_SCALE);
    float tInh = (float) (SAMPLING_RATE / INH_SYNAPSE_TIME_SCALE);
    for (int index = 0; index < SYNAPSE_FILTER_ORDER * 2; index++)
    {
        obj->synapseCoeff[index] = index < SYNAPSE_FILTER_ORDER ?
                                   (cl_float)(100.0f * index * tExc * expf( - index * tExc)) :
                                   (cl_float)(100.0f * (index - SYNAPSE_FILTER_ORDER) * tInh * expf( - (index - SYNAPSE_FILTER_ORDER) * tInh));
        //LOGD("The synapse coefficients are: \tcoefficient %d \tvalue %f", index, (float) (obj->synapseCoeff[index]));
    }

    // Synaptic weights and relative flags initialization
    for (int index = 0; index < NUM_SYNAPSES * NUM_NEURONS; index++) {
        //obj->synapseWeights[index] = index % 2 == 0 ? 1.0f : - 0.33f;
        obj->updateWeightsFlags[index] = 0.0f;
        obj->synapseWeights[index] = 0.0f;
    }

    // Initialization of dynamic variables, of the buffer which will hold the total synaptic
    // current for each neuron and that containing the postsynaptic firing rates
    for (int index = 0; index < NUM_NEURONS; index++)
    {
        obj->neuronalDynVar[2 * index] = -65.0f;
        obj->neuronalDynVar[2 * index + 1] = -13.0f;
        obj->current[index] = (cl_int)0;
        obj->postsynFiringRates[index] = (cl_float)0.0f;
    }

    // Un-map the pointers used to initialize the memory buffers
    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[0], obj->synapseCoeff, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->synapseWeights, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[3], obj->current, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[6], obj->postsynFiringRates, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[7], obj->updateWeightsFlags, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    return (long) obj;
}

extern "C" jbyteArray Java_com_example_overmind_SimulationService_simulateDynamics(
        JNIEnv *env, jobject thiz, jbyteArray jSynapseInput, jlong jOpenCLObject, jfloatArray jSimulationParameters,
        jbyteArray jWeights, jintArray jWeightsIndexes, jfloatArray jPresynFiringRates, jbyteArray jUpdateWeightsFlags) {
    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)jOpenCLObject;

    jbyte *synapseInput = env->GetByteArrayElements(jSynapseInput, JNI_FALSE);
    jfloat *simulationParameters = env->GetFloatArrayElements(jSimulationParameters, JNI_FALSE);
    jbyte *weights = env->GetByteArrayElements(jWeights, JNI_FALSE);
    jint *weightsIndexes = env->GetIntArrayElements(jWeightsIndexes, JNI_FALSE);
    jfloat *presynFiringRates = env->GetFloatArrayElements(jPresynFiringRates, JNI_FALSE);
    jbyte *updateWeightsFlags = env->GetByteArrayElements(jUpdateWeightsFlags, JNI_FALSE);

    int numOfNewWeights = env->GetArrayLength(jWeights);
    int synapseInputLength = env->GetArrayLength(jSynapseInput);
    int numOfActiveSynapses = synapseInputLength / maxNumberMultiplications;

    /* [Input initialization] */

    /*
     * Map the memory buffers that holds the input of the synapses and the presynaptic firing rates.
     */

    bool mapMemoryObjectsSuccess = true;

    // Map the buffer
    obj->synapseInput = (cl_uchar *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[2], CL_TRUE, CL_MAP_WRITE, 0, synapseInputBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->presynFiringRates = (cl_float *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[5], CL_TRUE, CL_MAP_WRITE, 0, presynFiringRatesBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    // Catch eventual errors
    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    // Initialize the buffer on the CPU side with the data received from Java Native Interface
    for (int i = 0; i < numOfActiveSynapses; i++)
    {
        obj->presynFiringRates[i] = (cl_float)presynFiringRates[i];
        for (int j = 0; j < maxNumberMultiplications; j++)
        {
            obj->synapseInput[i * maxNumberMultiplications + j] = (cl_uchar)synapseInput[i * maxNumberMultiplications + j];
        }
    }

    /*
    for (int i = 0; i < synapseInputLength; i++) {
        obj->synapseInput[i] = (cl_uchar)synapseInput[i];
    }
    */

    // Un-map the buffer
    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[2], obj->synapseInput, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[5], obj->presynFiringRates, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    // Release the java array since the data has been passed to the memory buffer
    env->ReleaseByteArrayElements(jSynapseInput, synapseInput, 0);
    env->ReleaseFloatArrayElements(jPresynFiringRates, presynFiringRates, 0);

    /*
     * If the weights have changed, initialize them.
     */

    // Proceed only if some weights have been changed.
    if (numOfNewWeights != 0) {

        obj->synapseWeights = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_WRITE, 0, synapseWeightsBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        obj->updateWeightsFlags = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[7], CL_TRUE, CL_MAP_WRITE, 0, synapseWeightsBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        if (!mapMemoryObjectsSuccess)
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Failed to map buffer");
        }

        // Enter the block if the weights array is sparse.
        if (numOfNewWeights != numOfActiveSynapses * NUM_NEURONS)
        {
            LOGD("test0");
            for (int i = 0; i < numOfNewWeights; i++)
                obj->synapseWeights[weightsIndexes[i]] = (cl_float) (MIN_WEIGHT * weights[i]);
        }
        else
        {
            LOGD("test1");
            for (int i = 0; i < numOfActiveSynapses * NUM_NEURONS; i++) {
                obj->synapseWeights[i] = (cl_float) (MIN_WEIGHT * weights[i]);
                obj->updateWeightsFlags[i] = (cl_float) updateWeightsFlags[i]; // It is assumed that whenever the weights of all the active synapses are updated, so are the respective flags.
            }
        }

        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->synapseWeights, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }

        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[7], obj->updateWeightsFlags, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }

        env->ReleaseByteArrayElements(jWeights, weights, 0);
        env->ReleaseIntArrayElements(jWeightsIndexes, weightsIndexes, 0);
        env->ReleaseByteArrayElements(jUpdateWeightsFlags, updateWeightsFlags, 0);

    }

    // How many elements of synapseInput[] does a single kernel compute?
    int inputsPerKernel = obj->floatVectorWidth * 4; // Each input is a char, therefore the char vector width is floatVectorWidth * 4

    LOGD("%d", synapseInputLength);

    // How many kernels are needed to server all the active synapses for a single neuron?
    int localSize = synapseInputLength % inputsPerKernel == 0 ?
                    synapseInputLength / inputsPerKernel : synapseInputLength / inputsPerKernel + 1;

    // Map the local size value to the gpu memory buffer
    obj->localSize = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[4], CL_TRUE, CL_MAP_WRITE, 0,
                                                  sizeof(cl_int), 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->localSize[0] = (cl_int)localSize;

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[4], obj->localSize, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    /* [Input initialization] */

    /* [Set Kernel Arguments] */

    // Tell the kernels which data to use before they are scheduled
    bool setKernelArgumentSuccess = true;
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 0, sizeof(cl_mem), &obj->memoryObjects[0]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 1, sizeof(cl_mem), &obj->memoryObjects[1]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 2, sizeof(cl_mem), &obj->memoryObjects[2]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 3, sizeof(cl_mem), &obj->memoryObjects[3]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 4, sizeof(cl_mem), &obj->memoryObjects[4]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 5, sizeof(cl_mem), &obj->memoryObjects[5]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 6, sizeof(cl_mem), &obj->memoryObjects[6]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 7, sizeof(cl_mem), &obj->memoryObjects[7]));

    // Catch eventual errors
    if (!setKernelArgumentSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to set OpenCL kernel arguments");
    }

    // Number of kernel instances. If the work group size allowed is smaller than that anticipated
    // some synapses won't be served at all.
    size_t globalWorksize[1] = {(size_t )localSize * NUM_NEURONS};

    bool openCLFailed = false;

    // Enqueue the kernel
    if (!checkSuccess(clEnqueueNDRangeKernel(obj->commandQueue, obj->kernel, 1, NULL, globalWorksize, NULL, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to enqueue OpenCL kernel");
        openCLFailed = true;
    }

    // Wait for kernel execution completion
    if (!checkSuccess(clFinish(obj->commandQueue)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed waiting for kernel execution to finish");
        openCLFailed = true;
    }

    // If an error occurred return an empty byte array
    if (openCLFailed) {
        jbyteArray errorByte = env->NewByteArray(0);
        return errorByte;
    }

    /* [Kernel execution] */

    /* [Simulate the neuronal dynamics] */

    // Map the OpenCL memory buffer so that it can be accessed by the host
    obj->current = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[3], CL_TRUE, CL_MAP_READ | CL_MAP_WRITE, 0, currentBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->postsynFiringRates = (cl_float *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[6],
                                                             CL_TRUE, CL_MAP_READ | CL_MAP_WRITE, 0, postsynFiringRatesBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    // Array holding the spikes fired by the neurons. Each bit represents a spike if it is set.
    char actionPotentials[dataBytes];

    // Simulate the neuronal dynamics using the current computed by the OpenCL implementation
    for (short workId = 0; workId < NUM_NEURONS; workId++)
    {
        // Convert back from int to float
        float unsignedCurrentFloat = (float)(obj->current[workId]) / 32768000.0f;
        float currentFloat = obj->current[workId] > 0 ? unsignedCurrentFloat : (-unsignedCurrentFloat);

        // Compute the potential and the recovery variable using Euler integration and Izhikevich model
        potentialVar += 0.04f * pow(potentialVar, 2) + 5.0f * potentialVar + 140.0f - recoveryVar + currentFloat + IPar;
        recoveryVar += aPar * (bPar * potentialVar - recoveryVar);

        // Set the bits corresponding to the neurons that have fired and update the moving average of the firing rates
        if (potentialVar >= 30.0f)
        {
            actionPotentials[(short)(workId / 8)] |= (1 << (workId - (short)(workId / 8) * 8));
            recoveryVar += dPar;
            potentialVar = cPar;
            obj->postsynFiringRates[workId] += (cl_float)(MEAN_RATE_INCREMENT * (1 - obj->postsynFiringRates[workId]));
        }
        else
        {
            actionPotentials[(short)(workId / 8)] &= ~(1 << (workId - (short)(workId / 8) * 8));
            obj->postsynFiringRates[workId] -= (cl_float)(MEAN_RATE_INCREMENT * obj->postsynFiringRates[workId]);
        }

        obj->current[workId] = (cl_int)0;
    }
    /* [Simulate the neuronal dynamics] */

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[3], obj->current, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[6], obj->postsynFiringRates, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    // Release the array storing the simulation parameters
    env->ReleaseFloatArrayElements(jSimulationParameters, simulationParameters, 0);

    // Create the array where to store the output
    jbyteArray outputSpikes = env->NewByteArray(dataBytes);
    // Copy the content in the buffer to the java array, using the pointer created by the OpenCL implementation
    env->SetByteArrayRegion(outputSpikes, 0, dataBytes, reinterpret_cast<jbyte*>(&actionPotentials));

    return outputSpikes;
}

extern "C" void Java_com_example_overmind_SimulationService_closeOpenCL(
        JNIEnv *env, jobject thiz,  jlong jOpenCLObject) {
    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *) jOpenCLObject;

    if (!cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects))
    {
        LOGE("Failed to clean-up OpenCL");
    };

    // Delete the memory allocated by the host
    delete obj->neuronalDynVar;

    free(obj);
}
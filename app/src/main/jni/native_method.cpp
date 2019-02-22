#include <native_method.h>

short NUM_SYNAPSES = 1024;
short NUM_NEURONS = 1024;

// Maximum number of multiplications needed to compute the current response of a synapse
int maxMultiplications = 0;

// Array whose elements contain how many neurons each layer has
int *layersNeurons = nullptr;

// Array whose elements contain how many synapses each layer has
int *layersSynapses = nullptr;

// How many layers the populations matrix is made of
int numOfLayers = 0;

int synapsesOffset = 0, neuronsOffset = 0;

// Size of the buffers needed to store data
size_t synapseCoeffBufferSize;
size_t synapseInputBufferSize;
size_t synapseWeightsBufferSize;
size_t synapseIndexesBufferSize;
size_t currentBufferSize;
size_t neuronsIndexesBufferSize;
size_t presynFiringRatesBufferSize;
size_t postsynFiringRatesBufferSize;

// Debug variables
int counter = 100;

extern "C" jlong Java_com_example_overmind_SimulationService_initializeOpenCL (
        JNIEnv *env, jobject thiz, jstring jKernel, jshort jNumOfNeurons, jint jFilterOrder, jshort jNumOfSynapses) {

    // Reset sensible fields
    numOfLayers = 0;

    delete[] layersSynapses;
    layersSynapses = new int[1];

    // Create a new openCL object since the one created in the getNumOfSynapses function could not be returned
    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)malloc(sizeof(struct OpenCLObject)); // TODO: perhaps the pointer can be stored in this file like the buffer size variables?

    // Compute the size of the GPU buffers
    //SYNAPSE_FILTER_ORDER = jFilterOrder;
    NUM_SYNAPSES = jNumOfSynapses;
    NUM_NEURONS = jNumOfNeurons;
    maxMultiplications = (int) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD);
    synapseCoeffBufferSize = SYNAPSE_FILTER_ORDER * 2 * sizeof(cl_float);

    /*
     * Worst case scenario for every neuron there is a different population which is connected to a different terminal
     * hence we can have as many NUM_SYNAPSES * NUM_NEURONS different synapses
     */

    // TODO: Put a limit to how small populations can be on the server side to diminish the memory
    // TODO: footprints

    synapseInputBufferSize =  maxMultiplications * (NUM_SYNAPSES * NUM_NEURONS)  * sizeof(cl_uchar);
    presynFiringRatesBufferSize = (NUM_SYNAPSES * NUM_NEURONS) * sizeof(cl_float);

    synapseIndexesBufferSize = NUM_SYNAPSES * NUM_NEURONS * sizeof(cl_ushort);
    synapseWeightsBufferSize = NUM_SYNAPSES * NUM_NEURONS * sizeof(cl_float);
    currentBufferSize = 2 * NUM_NEURONS * sizeof(cl_int); // We store separately the current from the excitatory synapses and from the inh ones
    postsynFiringRatesBufferSize = NUM_NEURONS * sizeof(cl_float);
    neuronsIndexesBufferSize = synapseIndexesBufferSize;

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
    obj->numberOfMemoryObjects = 10;

    // Allocate memory from the host
    double *neuronalDynVar = new double[2 * NUM_NEURONS];
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

    obj->memoryObjects[4] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, neuronsIndexesBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[5] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, presynFiringRatesBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[6] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, postsynFiringRatesBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[7] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseWeightsBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[8] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, synapseIndexesBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[9] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR,
                                           sizeof(cl_uint), NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[10] = clCreateBuffer(obj->context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, postsynFiringRatesBufferSize, NULL, &obj->errorNumber);
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

    obj->weightsReservoir = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[10], CL_TRUE, CL_MAP_WRITE, 0, postsynFiringRatesBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    // Initialize the coefficients array by sampling the exponential kernel of the synapse filter
    // The model for the currents come from https://www.frontiersin.org/articles/10.3389/neuro.10.009.2009/full
    float tExc = (float) (SAMPLING_RATE / EXC_SYNAPSE_TIME_SCALE);
    float tInh = (float) (SAMPLING_RATE / INH_SYNAPSE_TIME_SCALE);
    for (int index = 0; index < SYNAPSE_FILTER_ORDER * 2; index++)
    {
        obj->synapseCoeff[index] = index < SYNAPSE_FILTER_ORDER ?
                                   (cl_float)(100 * index * tExc * expf( - index * tExc)) :
                                   (cl_float)(100 * (index - SYNAPSE_FILTER_ORDER) * tInh * expf( - (index - SYNAPSE_FILTER_ORDER) * tInh));
        //LOGD("The synapse coefficients are: \tcoefficient %d \tvalue %f", index, (float) (obj->synapseCoeff[index]));
    }

    // Synaptic weights and relative flags initialization
    for (int index = 0; index < NUM_SYNAPSES * NUM_NEURONS; index++) {
        obj->synapseWeights[index] = index % 2 == 0 ? (cl_float)1.0f : (cl_float)(- 0.33f);
        obj->updateWeightsFlags[index] = (cl_float)0.0f;
        //obj->synapseWeights[index] = 0.5f;
    }

    // Initialization of dynamic variables, of the buffer which will hold the total synaptic
    // current for each neuron and that containing the postsynaptic firing rates
    for (int index = 0; index < NUM_NEURONS; index++)
    {
        obj->neuronalDynVar[2 * index] = -65.0f;
        obj->neuronalDynVar[2 * index + 1] = 8.0f;
        obj->current[2 * index] = (cl_int)0;
        obj->current[2 * index + 1] = (cl_int)0;
        obj->postsynFiringRates[index] = (cl_float)0.0f;

        // TODO: Initialization of the weights reservoir should happen in the simulateDynamics function,
        // TODO: using a value passed by the server. Moreover, the conversion factor should be computed
        // TODO: using the same variables that are then passed to the OpenCL kernel (the variables being
        // TODO: the learning rate, an arbitrary multiplicative factor and the number of plastic synapses
        // TODO: per neuron).
        obj->weightsReservoir[index] = 500 * 2000;
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

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[10], obj->weightsReservoir, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    return (long) obj;
}

extern "C" jbyteArray Java_com_example_overmind_SimulationService_simulateDynamics(
        JNIEnv *env, jobject thiz, jbyteArray jSynapseInput, jlong jOpenCLObject, jfloatArray jSimulationParameters,
        jbyteArray jWeights, jintArray jWeightsIndexes, jfloatArray jPresynFiringRates, jbyteArray jUpdateWeightsFlags,
        jobjectArray jIndexesMatrix, jobjectArray jNeuronsMatrix) {
    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)jOpenCLObject;

    jbyte *synapseInput = env->GetByteArrayElements(jSynapseInput, JNI_FALSE);
    jfloat *simulationParameters = env->GetFloatArrayElements(jSimulationParameters, JNI_FALSE);
    jbyte *weights = env->GetByteArrayElements(jWeights, JNI_FALSE);
    jint *weightsIndexes = env->GetIntArrayElements(jWeightsIndexes, JNI_FALSE);
    jfloat *presynFiringRates = env->GetFloatArrayElements(jPresynFiringRates, JNI_FALSE);
    jbyte *updateWeightsFlags = env->GetByteArrayElements(jUpdateWeightsFlags, JNI_FALSE);

    int numOfNewWeights = env->GetArrayLength(jWeights);
    int weightsFlagLength = env->GetArrayLength(jUpdateWeightsFlags);
    int matrixDepth = env->GetArrayLength(jIndexesMatrix);
    int inputNeurons = (env->GetArrayLength(jSynapseInput)) / maxMultiplications;

    /*
     * If the weights have changed, initialize them.
     */

    bool mapMemoryObjectsSuccess = true;

    // Proceed inside if the flags that tell which weights must be updated have changed.
    if (weightsFlagLength != 0) {

        //LOGD("Updating weightsFLags");

        obj->updateWeightsFlags = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[7], CL_TRUE, CL_MAP_WRITE | CL_MAP_READ, 0, synapseWeightsBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        if (!mapMemoryObjectsSuccess)
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Failed to map buffer");
        }

        for (int i = 0; i < weightsFlagLength; i++) {
            obj->updateWeightsFlags[i] = (cl_float) updateWeightsFlags[i];
            //LOGD("updateWeightsFlags %f i %d", (float) updateWeightsFlags[i], i);
        }

        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[7], obj->updateWeightsFlags, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }

        env->ReleaseByteArrayElements(jUpdateWeightsFlags, updateWeightsFlags, 0);

    }

    // Proceed only if some weights have been changed.
    if (numOfNewWeights != 0) {

        obj->synapseWeights = (cl_float*)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_WRITE | CL_MAP_READ, 0, synapseWeightsBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        //LOGD("Updating weights");

        if (!mapMemoryObjectsSuccess)
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Failed to map buffer");
        }

        /*
         * Weights arrive from the server in the range [0, 1], spaced by a value DELTA_WEIGHT. The variable
         * maxWeight is used to restrict the values in the range [0, maxWeight].
         */

        float maxWeight = 1.0f,
        factor = DELTA_WEIGHT * maxWeight;

        //LOGD("jWeightsIndexes length %d numOfNewWeights %d ", env->GetArrayLength(jWeightsIndexes), numOfNewWeights);

        // Enter the block if the weights array is sparse.
        if (env->GetArrayLength(jWeightsIndexes) == numOfNewWeights) // The two numbers are equal if for each index there is a corresponding weight
        {
            for (int i = 0; i < numOfNewWeights; i++) {
                obj->synapseWeights[weightsIndexes[i]] = (cl_float) (factor * weights[i]);
                //LOGD("synapticWeight %d is %f ", weightsIndexes[i], obj->synapseWeights[weightsIndexes[i]]);
            }

        }
        else // Enter the block if the weights array is filled completely.
        {
            for (int i = 0; i < numOfNewWeights; i++) {
                obj->synapseWeights[i] = (cl_float) (factor * weights[i]);
                //LOGD("synapticWeight %d is %f ",i, obj->synapseWeights[i]);
            }
        }

        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->synapseWeights, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }

        env->ReleaseByteArrayElements(jWeights, weights, 0);
        env->ReleaseIntArrayElements(jWeightsIndexes, weightsIndexes, 0);

    }

    /*
     * If the matrix of the populations has changed the indexes of the synapses and the neurons  will have too,
     * therefore the respective memory buffers must be re-initialized with the new indexes
     */

    if (matrixDepth != 0) { // If the order of the matrix is 0 that means that no change has occurred
        neuronsOffset = synapsesOffset = 0;

        // Dynamic memory allocation
        delete[] layersNeurons;
        layersNeurons = new int[matrixDepth];

        delete[] layersSynapses;
        layersSynapses = new int[matrixDepth];

        // Map the memory buffers used to store the indexes
        obj->synapseIndexes = (cl_ushort *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[8], CL_TRUE, CL_MAP_WRITE | CL_MAP_READ, 0, synapseIndexesBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        obj->neuronsIndexes = (cl_ushort *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[4], CL_TRUE, CL_MAP_WRITE | CL_MAP_READ, 0, neuronsIndexesBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        if (!mapMemoryObjectsSuccess)
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Failed to map buffer");
        }

        // Matrix depth can potentially change at every iteration and is different from zero only when the terminal is
        // updated whereas the number of layers changes only when a layer is added or subtracted to the matrix
        numOfLayers = matrixDepth;

        // Iterate over the rows of the populations matrix
        for (int i = 0; i < matrixDepth; i++) {

            // Get the array containing the indexes of the synapses of the i-th row
            jobject indexesRow = env->GetObjectArrayElement(jIndexesMatrix, (jsize) i);
            jintArray jIndexesArray = (jintArray) indexesRow;
            jint *indexesArray = env->GetIntArrayElements(jIndexesArray, JNI_FALSE);

            // Get the aray containing the indexes of the neurons
            jobject neuronsRow = env->GetObjectArrayElement(jNeuronsMatrix, (jsize) i);
            jintArray jNeuronsArray = (jintArray) neuronsRow;
            jint *neuronsArray = env->GetIntArrayElements(jNeuronsArray, JNI_FALSE);

            // Get how many input synapses this layer has
            layersSynapses[i] = env->GetArrayLength(jIndexesArray);

            int lastNeuronIndex = 0;
            layersNeurons[i] = 0;

            // Iterate over the synapses of all the populations of a given layer/row
            for (int j = 0; j < layersSynapses[i]; j++) {

                if (neuronsArray[j] != lastNeuronIndex) {
                    lastNeuronIndex = neuronsArray[j];
                    layersNeurons[i]++;
                }

                obj->synapseIndexes[synapsesOffset + j] = (cl_ushort)indexesArray[j];

                obj->neuronsIndexes[synapsesOffset + j] = (cl_ushort)(neuronsOffset +
                        layersNeurons[i]);
            }

            // This last incrementation accounts for the last neuron, which otherwise is not considered as the counter
            // is incremented only when the neuron index changes
            layersNeurons[i]++;

            synapsesOffset += layersSynapses[i];
            neuronsOffset += layersNeurons[i];
            env->ReleaseIntArrayElements(jIndexesArray, indexesArray, 0);
            env->ReleaseIntArrayElements(jNeuronsArray, neuronsArray, 0);


            //LOGD("\n synapsesOffset %d layerSynapses %d neuronsOffset %d layerNeurons %d i %d matrixDepth %d \n",
                 //synapsesOffset, layersSynapses[i], neuronsOffset, layersNeurons[i], i, matrixDepth);
        }

        // Now that the initialization is complete the buffers can be unmapped
        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[8], obj->synapseIndexes, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }

        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[4], obj->neuronsIndexes, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }
    }

    /*
     * The loop iterates over the layers that make up the organization of the populations. For each iteration the memory buffers are
     * mapped and populated, the kernels are scheduled, the neuronal dynamics are simulated and the synaptic inputs for the next
     * iteration are built.
     */

    // How many neurons have been considered up to this point
    int neuronsComputed = 0;

    // The number of bytes needed to hold all the spikes
    short dataBytes = (neuronsOffset % 8) == 0 ? (short)(neuronsOffset / 8) :
                        (short)(neuronsOffset / 8 + 1);

    // Array holding the spikes fired by the neurons. Each bit represents a spike if it is set.
    char actionPotentials[dataBytes];

    // Iterate over the input layers
    for (int i = 0; i <= numOfLayers; i++) {

        /* Open the buffers */

        obj->synapseInput = (cl_uchar *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[2], CL_TRUE, CL_MAP_READ| CL_MAP_WRITE, 0, synapseInputBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        obj->presynFiringRates = (cl_float *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[5], CL_TRUE, CL_MAP_WRITE, 0, presynFiringRatesBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        obj->current = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[3], CL_TRUE, CL_MAP_READ | CL_MAP_WRITE, 0, currentBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        obj->postsynFiringRates = (cl_float *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[6],
                                                                 CL_TRUE, CL_MAP_READ | CL_MAP_WRITE, 0, postsynFiringRatesBufferSize, 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        obj->globalIdOffset = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[9], CL_TRUE, CL_MAP_WRITE, 0, sizeof(cl_uint), 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        obj->weightsReservoir = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[10], CL_TRUE, CL_MAP_READ, 0, sizeof(cl_int), 0, NULL, NULL, &obj->errorNumber);
        mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

        if (!mapMemoryObjectsSuccess)
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Failed to map buffer");
        }

        /* Map the buffers */

        if (i != 0) {

            // Call function that compute the neuronal dynamics
            computeNeuronalDynamics(neuronsComputed, layersNeurons[i - 1], obj->current,
                                    simulationParameters, obj->neuronalDynVar,
                                    obj->postsynFiringRates, actionPotentials, obj->weightsReservoir);

            if (i != numOfLayers) {
                obj->globalIdOffset[0] += (cl_uint) layersSynapses[i - 1] / maxMultiplications;

                // Build the synaptic input from the last action potentials. inputNeurons is passed because
                // the synapseInput buffer also contains the inputs coming from the presynaptic terminals
                buildSynapticInput(inputNeurons + neuronsComputed, actionPotentials,
                                   layersNeurons[i - 1], maxMultiplications, obj->synapseInput);

                // Copy the postsynaptic firing rates to the presynaptic buffer
                for (int j = neuronsComputed; j < neuronsComputed + layersNeurons[i - 1]; j++) {
                    obj->presynFiringRates[inputNeurons + j] = obj->postsynFiringRates[j];
                }

                neuronsComputed += layersNeurons[i - 1];
            }
        } else {
            obj->globalIdOffset[0] = (cl_uint) 0;

            //LOGD("inputNeurons %d", inputNeurons);

            for (int j = 0; j < inputNeurons; j++) {

                // Load the presynaptic firing rates
                obj->presynFiringRates[j] = (cl_float) presynFiringRates[j];

                // Load the inputs of the synapses - the operation must be repeated for each multiplication/time step

                for (int k = 0; k < maxMultiplications; k++) {
                    obj->synapseInput[j * maxMultiplications +
                                      k] = (cl_uchar) synapseInput[j * maxMultiplications + k];
                }

            }
        }

        /* Un-map the buffers */

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

        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[9], obj->globalIdOffset, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }

        if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[10], obj->weightsReservoir, 0, NULL, NULL)))
        {
            cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
            LOGE("Unmap memory objects failed");
        }

        /* Execute the kernels */

        if (i < numOfLayers) {

            // Tell the kernels which data to use before they are scheduled TODO: Maybe this operation can be done once before the for loop.
            bool setKernelArgumentSuccess = true;
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 0, sizeof(cl_mem), &obj->memoryObjects[0]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 1, sizeof(cl_mem), &obj->memoryObjects[1]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 2, sizeof(cl_mem), &obj->memoryObjects[2]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 3, sizeof(cl_mem), &obj->memoryObjects[3]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 4, sizeof(cl_mem), &obj->memoryObjects[4]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 5, sizeof(cl_mem), &obj->memoryObjects[5]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 6, sizeof(cl_mem), &obj->memoryObjects[6]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 7, sizeof(cl_mem), &obj->memoryObjects[7]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 8, sizeof(cl_mem), &obj->memoryObjects[8]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 9, sizeof(cl_mem), &obj->memoryObjects[9]));
            setKernelArgumentSuccess &= checkSuccess(
                    clSetKernelArg(obj->kernel, 10, sizeof(cl_mem), &obj->memoryObjects[10]));

            // Catch eventual errors
            if (!setKernelArgumentSuccess) {
                cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel,
                              obj->memoryObjects, obj->numberOfMemoryObjects);
                LOGE("Failed to set OpenCL kernel arguments");
            }

            // Number of kernel instances.
            size_t globalWorksize[1] = {(size_t) layersSynapses[i] / obj->floatVectorWidth};

            bool openCLFailed = false;

            // Enqueue the kernel
            if (!checkSuccess(
                    clEnqueueNDRangeKernel(obj->commandQueue, obj->kernel, 1, NULL, globalWorksize,
                                           NULL, 0, NULL, NULL))) {
                cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel,
                              obj->memoryObjects, obj->numberOfMemoryObjects);
                LOGE("Failed to enqueue OpenCL kernel");
                openCLFailed = true;
            }

            // Wait for kernel execution completion
            if (!checkSuccess(clFinish(obj->commandQueue))) {
                cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel,
                              obj->memoryObjects, obj->numberOfMemoryObjects);
                LOGE("Failed waiting for kernel execution to finish");
                openCLFailed = true;
            }

            // If an error occurred return an empty byte array
            if (openCLFailed) {
                jbyteArray errorByte = env->NewByteArray(0);
                return errorByte;
            }

        }
    }

    // The array used to move data from the java side to the native one can be released
    env->ReleaseByteArrayElements(jSynapseInput, synapseInput, 0);
    env->ReleaseFloatArrayElements(jPresynFiringRates, presynFiringRates, 0);

    counter = printSynapticMaps(counter, obj, synapseWeightsBufferSize, NUM_SYNAPSES);

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
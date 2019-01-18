/* This kernel is for devices with 128 bits wide registers. 
   It computes 4 synapses at a time. */

#define SYN_X_WI 4 // Synapses per work item
#define SYNAPSE_FILTER_ORDER 16
#define LEARNING_RATE 0.05f
#define NEURONS_X_POP 8
#define MAX_WEIGHT 10.0f
#define A 2
#define B 0 
#define C 4
#define REFERENCE_RATE 1.0f
 
__kernel __attribute__((vec_type_hint(float4)))
void simulate_dynamics(__constant float* restrict coeff, __global float* restrict weights, // TODO: Coalesce some of the buffers into one?
		       __global uchar* restrict input,  __global int* restrict current,
		       __global ushort* restrict neuronsIndexes, __global float* restrict presynFiringRates,
		       __global float* restrict postsynFiringRates, __global float* restrict updateWeightsFlags,
		       __global ushort* restrict synIndexes, __constant uint* restrict globalIdOffset)
// TODO: input, presynFiringRates, postsynFiringRates and updateWeightsFlags could be __constant...
{
  // Id of the work item
  uint globalId = get_global_id(0) + globalIdOffset[0];

  // Id of the neuron to which the synapses belong
  ushort neuronIndex = neuronsIndexes[SYN_X_WI * globalId];

  ushort neuronIdxInPop = neuronIndex % NEURONS_X_POP;

  // Id of the input synapses
  ushort4 preFRIndexesVec = vload4(globalId, synIndexes);
  ushort4 synIndexesVec = ((ushort)SYN_X_WI) * preFRIndexesVec;
  
  // Load the synaptic input (the coeffiecients of the filter kernel)
  uchar16 synInput = (uchar16)(input[synIndexesVec.x], input[synIndexesVec.x + 1], input[synIndexesVec.x + 2], input[synIndexesVec.x + 3],
			       input[synIndexesVec.y], input[synIndexesVec.y + 1], input[synIndexesVec.y + 2], input[synIndexesVec.y + 3],
			       input[synIndexesVec.z], input[synIndexesVec.z + 1], input[synIndexesVec.z + 2], input[synIndexesVec.z + 3],
			       input[synIndexesVec.w], input[synIndexesVec.w + 1], input[synIndexesVec.w + 2], input[synIndexesVec.w + 3]);
			       
  // Synaptic weights 
  float4 weightsVec = vload4(globalId, weights);

  // Weights flags: indicates whether the respective weights should be updated or not
  float4 weightsFlagsVec = vload4(globalId, updateWeightsFlags);

  // Firing rates of the presynaptic neurons
  float4 preFiringRatesVec = (float4)(presynFiringRates[preFRIndexesVec.x], presynFiringRates[preFRIndexesVec.y],
				      presynFiringRates[preFRIndexesVec.z], presynFiringRates[preFRIndexesVec.w]);

  /*
   * Inhibitory synapses coefficients, as well as their currents, must be stored in the second half of their
   * respective buffers. Therefore an offset is needed to access this portion of the buffers.
   */

  // Vector whose elements are 1 if the corresponding synapses are inhibitory, otherwise zero.
  // The nature of the synapse is determined by the sign of its weight
  float4 offset = step(-0.01f, -weightsVec);
  
  // Offsets to access the coefficients buffer
  uchar4 synInOffset = convert_uchar4(offset * SYNAPSE_FILTER_ORDER);
      
  // Coefficients of the synapse kernel
  float4 coeffVec = (float4)(coeff[synInput.s0 + synInOffset.x] + coeff[synInput.s1 + synInOffset.x] + coeff[synInput.s2 + synInOffset.x] + coeff[synInput.s3 + synInOffset.x],
			     coeff[synInput.s4 + synInOffset.y] + coeff[synInput.s5 + synInOffset.y] + coeff[synInput.s6 + synInOffset.y] + coeff[synInput.s7 + synInOffset.y],
			     coeff[synInput.s8 + synInOffset.z] + coeff[synInput.s9 + synInOffset.z] + coeff[synInput.sa + synInOffset.z] + coeff[synInput.sb + synInOffset.z],
			     coeff[synInput.sc + synInOffset.w] + coeff[synInput.sd + synInOffset.w] + coeff[synInput.se + synInOffset.w] + coeff[synInput.sf + synInOffset.w]);

  /*
   * Excitatory and inhibitory currents are computed separately since they must be stored in two different
   * portions of the same memory buffer
   */

  /* Inhibitory currents */

  float result = dot(coeffVec * offset, weightsVec);
  
  // OpenCL 1.1 doesn't support atomic operations on float, so we cast the result to int
  int resultInt = convert_int(result);

  // Increment the synaptic current
  atomic_add(&current[2 * neuronIndex + 1], resultInt);

  /* Excitatory currents */

  // Switching synapses the offset must be inverted 1 -> 0, 0 -> 1
  float4 excCoeff = coeffVec * (1 - offset);
  excCoeff = excCoeff - step(-0.01f, - excCoeff) * (1 - offset);  
  
  result = dot(excCoeff, weightsVec);
  resultInt = convert_int(result);
  atomic_add(&current[2 * neuronIndex], resultInt);

  // Update the weights

  float4 f_w = weightsVec / MAX_WEIGHT;
  //float4 f_w = native_exp(A * (weightsVec / MAX_WEIGHT - 1));
  //float4 f_w = 0.5f * (weightsVec / MAX_WEIGHT + 1);
  weightsVec += weightsFlagsVec *  LEARNING_RATE * 
    (preFiringRatesVec * (REFERENCE_RATE - postsynFiringRates[neuronIndex]) -
     postsynFiringRates[neuronIndex] * f_w);  

  /*
  weightsVec += weightsFlagsVec *  LEARNING_RATE *
    (preFiringRatesVec * (REFERENCE_RATE - postsynFiringRates[neuronIndex]) -
     pown(postsynFiringRates[neuronIndex], 2) * (1.0f - 0.5f * (MAX_WEIGHT - fabs(weightsVec)) / MAX_WEIGHT));
  */
  
  weightsVec = clamp(weightsVec, -MAX_WEIGHT, MAX_WEIGHT);
  
  vstore4(weightsVec, globalId, weights);
}

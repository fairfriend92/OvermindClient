/* This kernel is for devices with 128 bits wide registers. 
   It computes 4 synapses at a time. */

#define SYN_X_WI 4 // Synapses per work item
#define SYNAPSE_FILTER_ORDER 16
#define LEARNING_RATE 0.01f
#define NEURONS_X_POP 8
#define REFERENCE_RATE 1.00f
#define MAX_WEIGHT 10.0f
#define MIN_WEIGHT -MAXFLOAT
#define CONVERSION_FACTOR 100.0f / 0.05f
 
__kernel __attribute__((vec_type_hint(float4)))
void simulate_dynamics(__constant float* restrict coeff, __global float* restrict weights, // TODO: Coalesce some of the buffers into one?
		       __global uchar* restrict input,  __global int* restrict current,
		       __global ushort* restrict neuronsIndexes, __global float* restrict presynFiringRates,
		       __global float* restrict postsynFiringRates, __global float* restrict updateWeightsFlags,
		       __global ushort* restrict synIndexes, __constant uint* restrict globalIdOffset, __global uint* restrict weightsReservoir,
		       __global uint* restrict numOfExcWeights)
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
  //float4 offset = step(0.0f, - weightsVec);
  float4 offset = 0.5f * (fabs(weightsVec) - weightsVec) / fabs(weightsVec);
  
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
  float result = dot(coeffVec, weightsVec * offset);

  // Increment the synaptic current
  atomic_add(&current[2 * neuronIndex + 1], convert_int(result));

  /* Excitatory currents */

  // Switching synapses the offset must be inverted 1 -> 0, 0 -> 1
  offset = (1.0f - offset);
  result = dot(coeffVec, weightsVec * offset);
  atomic_add(&current[2 * neuronIndex], convert_int(result));

  // Update the weights

  float maxDw = weightsReservoir[neuronIndex] / (numOfExcWeights[neuronIndex] * CONVERSION_FACTOR);
  
  float4 dw = weightsFlagsVec * LEARNING_RATE *
    (preFiringRatesVec * maxDw / (weightsVec + maxDw) - (1.0f - preFiringRatesVec) * postsynFiringRates[neuronIndex]);
    
  weightsVec += dw;
  
  dw = dw * offset;
  atomic_sub(&weightsReservoir[neuronIndex],
	     convert_uint(CONVERSION_FACTOR * (dw.x + dw.y + dw.z + dw.w)));
  
  offset = 0.5f * (fabs(weightsVec) + weightsVec) / weightsVec - offset;
  atomic_add(&numOfExcWeights[neuronIndex],
	     convert_int(offset.x + offset.y + offset.z + offset.w)); 
    
  vstore4(weightsVec, globalId, weights);
}

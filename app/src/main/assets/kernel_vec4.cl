/* This kernel is for devices with 128 bits wide registers. 
   It computes 4 synapses at a time. */

#define SYN_X_WI 4 // Synapses per work item
#define SYNAPSE_FILTER_ORDER 16
#define LEARNING_RATE 1.0f
 
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
   * The coefficients of the synpatic filter for the inhibitory synapses are stored at 
   * the end of the memory buffer, therefore an offset needs to be added to the computation
   * of the index in the case a  negative weight reveals that the corresponding synapse
   * is inhibitory 
   */
  
  uchar4 offset = convert_uchar4(step(0.0f, - weightsVec) * SYNAPSE_FILTER_ORDER);
    
  // Coefficients of the synapse kernel
  float4 coeffVec = (float4)(coeff[synInput.s0 + offset.x] + coeff[synInput.s1 + offset.x] + coeff[synInput.s2 + offset.x] + coeff[synInput.s3 + offset.x],
			     coeff[synInput.s4 + offset.y] + coeff[synInput.s5 + offset.y] + coeff[synInput.s6 + offset.y] + coeff[synInput.s7 + offset.y],
			     coeff[synInput.s8 + offset.z] + coeff[synInput.s9 + offset.z] + coeff[synInput.sa + offset.z] + coeff[synInput.sb + offset.z],
			     coeff[synInput.sc + offset.w] + coeff[synInput.sd + offset.w] + coeff[synInput.se + offset.w] + coeff[synInput.sf + offset.w]);

  float result = dot(coeffVec, weightsVec);
  
  // OpenCL 1.1 doesn't support atomic operations on float, so we cast
  // the result to long
  int resultInt = convert_int(result);
  //resultInt = result > 0 ? resultInt : (-resultInt);

  // Update the weights using the rate based STDP learning rule
  weightsVec += weightsFlagsVec * LEARNING_RATE * postsynFiringRates[neuronIndex] * 
    (preFiringRatesVec - weightsVec * postsynFiringRates[neuronIndex]);
  
  vstore4(weightsVec, globalId, weights);

  // Increment the synaptic current
  atomic_add(&current[neuronIndex], resultInt);
}

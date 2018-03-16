/* This kernel is for devices with 128 bits wide registers. 
   It computes 4 synapses at a time. */

#define SYNAPSES_PER_KERNEL 4
#define SYNAPSE_FILTER_ORDER 16
#define LEARNING_RATE 0.1f

__kernel __attribute__((vec_type_hint(float4)))
void simulate_dynamics(__constant float* restrict coeff, __global float* restrict weights, // TODO: Coalesce some of the buffers into one?
		       __global uchar* restrict input,  __global int* restrict current,
		       __constant int* restrict localSize, __global float* restrict presynFiringRates,
		       __global float* restrict postsynFiringRates, __global float* restrict updateWeightsFlags)
// TODO: input, presynFiringRates, postsynFiringRates and updateWeightsFlags could be __constant...
{
  ushort workId = get_global_id(0) / localSize[0];
  ushort localId = get_global_id(0) - workId * localSize[0];
  uint weightsOffset = workId * localSize[0] * SYNAPSES_PER_KERNEL; // The weights buffer is NOT padded with 0s for the synapses that are not active

  uchar16 index = vload16(localId, input);

  // Synaptic weights 
  float4 weightsVec = vload4(localId, weights + weightsOffset);

  // Weights flags: indicates whether the respective weights should be updated or note. 
  float4 weightsFlagsVec = vload4(localId, updateWeightsFlags + weightsOffset);

  // Firing rates of the presynaptic neurons
  float4 preFiringRatesVec = vload4(localId, presynFiringRates);  
  
  /*
   * The coefficients of the synpatic filter for the inhibitory synapses are stored at 
   * the end of the memory buffer, therefore an offset needs to be added to the computation
   * of the index in the case a  negative weight reveals that the corresponding synapse
   * is inhibitory 
   */
  
  uchar4 offset = convert_uchar4(step(0.0f, - weightsVec) * SYNAPSE_FILTER_ORDER);
    
  // Coefficients of the synapse kernel
  float4 coeffVec = (float4)(coeff[index.s0 + offset.x] + coeff[index.s1 + offset.x] + coeff[index.s2 + offset.x] + coeff[index.s3 + offset.x],
			     coeff[index.s4 + offset.y] + coeff[index.s5 + offset.y] + coeff[index.s6 + offset.y] + coeff[index.s7 + offset.y],
			     coeff[index.s8 + offset.z] + coeff[index.s9 + offset.z] + coeff[index.sa + offset.z] + coeff[index.sb + offset.z],
			     coeff[index.sc + offset.w] + coeff[index.sd + offset.w] + coeff[index.se + offset.w] + coeff[index.sf + offset.w]);

  float result = dot(coeffVec, weightsVec) * 32768.0f;

  // OpenCL 1.1 doesn't support atomic operations on float, so we cast
  // the result to long
  int resultInt = convert_int(result);
  int increment = result > 0 ? resultInt : (-resultInt);

  // Update the weights using the rate based STDP learning rule
  weightsVec += weightsFlagsVec * LEARNING_RATE * postsynFiringRates[workId] * 
    (preFiringRatesVec - weightsVec * postsynFiringRates[workId]);
  vstore4(weightsVec, localId, weights + weightsOffset);

  // Increment the synaptic current
  atomic_add(&current[workId], increment);
}
    
  


/* This kernel is for devices with 128 bits wide registers. 
   It computes 4 synapses at a time. */

#define FLOAT_VECTOR_WIDTH 4
#define SYNAPSE_FILTER_ORDER 16

__kernel __attribute__((vec_type_hint(float4)))
void simulate_dynamics(__constant float* restrict coeff, __global float* restrict weights,
		       __global char* restrict input, volatile __global int* restrict current,
		       __constant int* restrict localSize) 
{
  ushort workId = get_global_id(0) / localSize[0];
  ushort localId = get_global_id(0) - workId * localSize[0];

  char16 index = vload16(localId, input);

  // Synaptic weights 
  float4 weightsVec = vload4(localId, weights + workId * (localSize[0] * 4));

  /*
   * The coefficients of the synpatic filter for the inhibitory synapses are stored at 
   * the end of the memory buffer, therefore an offset needs to be added to the computation
   * of the index in the case a  negative weight reveals that the corresponding synapse
   * is inhibitory 
   */
  
  char4 offset = convert_char4_rte(step((float4)(0.0f, 0.0f, 0.0f, 0.0f), - weightsVec) * SYNAPSE_FILTER_ORDER);

  // Coefficients of the synapse kernel
  float4 coeffVec = (float4)(coeff[index.s0 + offset.x] + coeff[index.s1 + offset.x] + coeff[index.s2 + offset.x] + coeff[index.s3 + offset.x],
			     coeff[index.s4 + offset.y] + coeff[index.s5 + offset.y] + coeff[index.s6 + offset.y] + coeff[index.s7 + offset.y],
			     coeff[index.s8 + offset.z] + coeff[index.s9 + offset.z] + coeff[index.sa + offset.z] + coeff[index.sb + offset.z],
			     coeff[index.sc + offset.w] + coeff[index.sd + offset.w] + coeff[index.se + offset.w] + coeff[index.sf + offset.w]);

  float result = dot(coeffVec, weightsVec) * 32768.0f;

  // OpenCL 1.1 doesn't support atomic operations on float, so we cast
  // the result to long
  int resultInt = convert_int_rte(result);
  int increment = result > 0 ? resultInt : (-resultInt);

  // Increment the synaptic current
  atomic_add(&current[workId], increment);

}
    
  


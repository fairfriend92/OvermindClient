#define NUMBER_OF_EXC_SYNAPSES 4

__kernel void synapse_vec4(__constant uint* restrict excCoefficient,
			   __constant uint* restrict inhCoefficient,
			   __constant uint* restrict synapseWeights,
			   __global uchar* restrict input,
			   __global int* restrict output)
{  
  ushort i = get_global_id(0);
  
  uchar4 index4 = vload4(i, input);
  int coeff;

  coeff = i < NUMBER_OF_EXC_SYNAPSES ?
    (int)(excCoefficient[index4.x] + excCoefficient[index4.y] + excCoefficient[index4.z] + excCoefficient[index4.w]) :
    (int)-(inhCoefficient[index4.x] + inhCoefficient[index4.y] + inhCoefficient[index4.z] + inhCoefficient[index4.w]); 
  
  output = atomic_add(output, coeff * synapseWeights[i]);  
}
  

#define NUMBER_OF_EXC_SYNAPSES 512

__kernel void mac_kernel_vec4(__constant uint* restrict excCoefficient,
			      __constant uint* restrict inhCoefficient,
			      __global uchar* restrict input,
			      __global int* restrict output)
{  
  ushort i = get_global_id(0);
  
  uchar4 index4 = vload4(i, input);
  int4 coeff4;

  if (i < NUMBER_OF_EXC_SYNAPSES)
    {
      coeff4 = (int4)(excCoefficient[index4.x], excCoefficient[index4.y], excCoefficient[index4.z], excCoefficient[index4.w]);
    }
  else
    {
      coeff4 = (int4)(-inhCoefficient[index4.x], -inhCoefficient[index4.y], -inhCoefficient[index4.z], -inhCoefficient[index4.w]);
    }
  
  output[i] = coeff4.x + coeff4.y + coeff4.z + coeff4.w;  

  uchar4 increment4;
  increment4.x = index4.x ? 1 : 0;
  increment4.y = index4.y ? 1 : 0;
  increment4.z = index4.z ? 1 : 0;
  increment4.w = index4.w ? 1 : 0;

  vstore4(index4 + increment4, i, input);  
}
  

__kernel void mac_kernel_vec4(__global float* restrict coefficient,
			      __global ushort* restrict input,
			      __global float* restrict output)
{  
  // Set i to be the ID of the kernel instance.
  ushort i = get_global_id(0);

  // TODO: take advantage of memory hierarchy (?)
  // TODO: take advantage of local work vs global work (?)
  
  ushort4 index4 = vload4(i, input);

  float4 coeff4 = (float4)(coefficient[index4.x], coefficient[index4.y], coefficient[index4.z], coefficient[index4.w]);
  
  output[i] = coeff4.x + coeff4.y + coeff4.z + coeff4.w;  

  ushort4 increment4;
  increment4.x = increment4.x ? 1 : 0;
  increment4.y = increment4.y ? 1 : 0;
  increment4.z = increment4.z ? 1 : 0;
  increment4.w = increment4.w ? 1 : 0;

  vstore4(index4 + increment4, i, input);  
}
  

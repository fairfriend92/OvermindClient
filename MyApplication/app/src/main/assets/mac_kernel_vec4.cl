__kernel void mac_kernel_vec4(__global float* restrict coefficient,
			      __global int* restrict input,
			      __global float* restrict output)
{
  // Set i to be the ID of the kernel instance.
  int i = get_global_id(0);
  
  int4 input4 = vload4(i, input);

  // Define the vector storing the indexes used to access the coefficients array
  int4 index4 = (input4 + (int4)(abs(input4.x), abs(input4.y), abs(input4.z), abs(input4.w))) / 2;

  // Retrieve the coefficients using the previously defined indexes 
  float4 coeff4 = (float4)(coefficient[index4.x], coefficient[index4.y], coefficient[index4.z], coefficient[index4.w]);

  // Sum the coefficients and store the result in the output array
  output[i] = dot(coeff4, (float4)(1.0f, 1.0f, 1.0f, 1.0f));

  // Define the vector storing the increments to the indexes
  index4 = (int4)(index4.x/input4.x, index4.y/input4.y, index4.z/input4.z, index4.w/input4.w);

  // Increment the indexes and store them in the input array
  vstore4(input4 + index4, i, input);
}
  

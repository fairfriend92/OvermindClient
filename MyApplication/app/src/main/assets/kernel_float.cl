#define potential neuronalDynVar[workId * 3]
#define recovery neuronalDynVar[workId * 3 + 1]
#define kahanCompensation neuronalDynVar[workId * 3 + 2]

#define a simulationParameters[0]
#define b simulationParameters[1]
#define c simulationParameters[2]
#define d simulationParameters[3]

__kernel void simulate_dynamics(__constant float* coeff, __constant uchar* weights,
				__constant uchar* input, __global int* current, __global int* counter,
				__global float* neuronalDynVar, __global uchar* actionPotentials, __constant float* simulationParameters)
{  
  uchar localId = get_local_id(0);
  ushort workId = get_group_id(0);

  uchar4 index = vload4(localId, input);

  float coeffVec = convert_float(coeff[index.s0] + coeff[index.s1] + coeff[index.s2] + coeff[index.s3]);

  float weightsVec = convert_float(weights[workId * 1024 + localId]);

  float result = coeffVec * weightsVec * 32768.0f;

  int resultInt = convert_int(result);
  int increment = result > 0 ? resultInt : (-resultInt);
  
  atomic_add(&current[workId], increment);
   
  atomic_inc(&counter[workId]);

  mem_fence(CLK_LOCAL_MEM_FENCE); 
 
  if (counter[workId] == get_local_size(0))
    {      
      float unsignedCurrentFloat = convert_float(current[workId]) / 32768000.0f;
      float currentFloat = current[workId] > 0 ? unsignedCurrentFloat : (-unsignedCurrentFloat);

      potential += 0.5f * (0.04f * pown(potential, 2) + 5.0f * potential + 140.0f - recovery + currentFloat);
      
      /*
      recovery = (0.5f * 0.02f * 0.2f * potential + recovery) / (1.0f + 0.5f * 0.02f);      
      recovery += 0.5f * 0.02f * (0.2f * potential - recovery);
      */
            
      float y = 0.5f * a * (b * potential - recovery) - kahanCompensation;
      float t = recovery + y;
      kahanCompensation = (t - recovery) - y;
      recovery = t;
                        
      if (potential >= 30.0f)
	{
	  actionPotentials[(ushort)(workId / 8)] |= (1 << (workId - (ushort)(workId / 8) * 8));
	  recovery += d;
	  potential = c;
	}
      else
	{
	  actionPotentials[(ushort)(workId / 8)] &= ~(1 << (workId - (ushort)(workId / 8) * 8));
	}      

      current[workId] = 0;
      counter[workId] = 0;
    }

}
    
  


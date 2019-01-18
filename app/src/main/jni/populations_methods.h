//
// Created by Rodolfo Rocco on 28/09/2018.
//

#ifndef OVERMINDCLIENT_POPULATIONS_METHODS_H
#define OVERMINDCLIENT_POPULATIONS_METHODS_H

#endif //OVERMINDCLIENT_POPULATIONS_METHODS_H

#include "native_method.h"

// Abbreviations
#define potentialVar neuronalDynVar[i * 2]
#define recoveryVar neuronalDynVar[i * 2 + 1]
#define aPar simulationParameters[0]
#define bPar simulationParameters[1]
#define cPar simulationParameters[2]
#define dPar simulationParameters[3]
#define IPar simulationParameters[4]

void buildSynapticInput(int neuronsComputed, char actionPotentials[], int numOfNeurons,
                        int maxMultiplications, cl_uchar synapticInput[]);
void computeNeuronalDynamics(int neuronsComputed, int numOfNeurons, cl_int current[], jfloat simulationParameters[],
                             double neuronalDynVar[], cl_float postsynFiringRates[], char actionPotentials[]);
int printSynapticMaps(int counter, OpenCLObject *obj, size_t synapseWeightsBufferSize, int NUM_SYNAPSES);
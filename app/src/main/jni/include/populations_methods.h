//
// Created by Rodolfo Rocco on 28/09/2018.
//

#ifndef OVERMINDCLIENT_POPULATIONS_METHODS_H
#define OVERMINDCLIENT_POPULATIONS_METHODS_H

#include <shared.h>
#include <common.h>

#define MEAN_RATE_INCREMENT 0.02f

// Abbreviations
#define potentialVar neuronalDynVar[i * 2]
#define recoveryVar neuronalDynVar[i * 2 + 1]
#define aPar simulationParameters[0]
#define bPar simulationParameters[1]
#define cPar simulationParameters[2]
#define dPar simulationParameters[3]
#define IPar simulationParameters[4]

#endif //OVERMINDCLIENT_POPULATIONS_METHODS_H

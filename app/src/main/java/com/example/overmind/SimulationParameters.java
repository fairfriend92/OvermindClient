package com.example.overmind;

class SimulationParameters {

    static private float recoveryScale;
    static private float recoverySensitivity;
    static private float resetPotential;
    static private float resetRecovery;
    static private float current;

    synchronized  static void setParameters(float a, float b, float c, float d, float i) {
        recoveryScale = a;
        recoverySensitivity = b;
        resetPotential = c;
        resetRecovery = d;
        current = i;
    }

    synchronized static float[] getParameters() {

        float[] paramArray = new float[5];

        paramArray[0] = recoveryScale;
        paramArray[1] = recoverySensitivity;
        paramArray[2] = resetPotential;
        paramArray[3] = resetRecovery;
        paramArray[4] = current;

        return paramArray;

    }

}

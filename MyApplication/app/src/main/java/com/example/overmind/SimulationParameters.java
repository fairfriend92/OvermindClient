package com.example.overmind;

class SimulationParameters {

    static private float recoveryScale;
    static private float recoverySensitivity;
    static private float resetPotential;
    static private float resetRecovery;

    synchronized  static void setParameters(float a, float b, float c, float d) {
        recoveryScale = a;
        recoverySensitivity = b;
        resetPotential = c;
        resetRecovery = d;
    }

    synchronized static float[] getParameters() {

        float[] paramArray = new float[4];

        paramArray[0] = recoveryScale;
        paramArray[1] = recoverySensitivity;
        paramArray[2] = resetPotential;
        paramArray[3] = resetRecovery;

        return paramArray;

    }

}

package com.example.overmind;

class SimulationParameters {

    static private double recoveryScale;
    static private double recoverySensitivity;
    static private double resetPotential;
    static private double resetRecovery;

    synchronized  static void setParameters(double a, double b, double c, double d) {
        recoveryScale = a;
        recoverySensitivity = b;
        resetPotential = c;
        resetRecovery = d;
    }

    synchronized static double[] getParameters() {

        double[] paramArray = new double[4];

        paramArray[0] = recoveryScale;
        paramArray[1] = recoverySensitivity;
        paramArray[2] = resetPotential;
        paramArray[3] = resetRecovery;

        return paramArray;

    }

}

package org.fe57.atomspectra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

class Calibration {
    private final ArrayList<Integer> ChannelList;  //list of calibration channels
    private final ArrayList<Double> EnergyList;   //list of calibration energies in keV
    private final double[] ApproximationList;     //list of channel approximation energies
    private double[] coeffArray;            //polynomial coefficients array in f(x)=a+bx+cx^2+dx^3+...

    public static Calibration defaultCalibration() {
        Calibration calibration = new Calibration().addLine(0, 0).addLine(8192, 3000);
        calibration.Calculate();
        return calibration;
    }

    //default constructor
    public Calibration() {
        ChannelList = new ArrayList<>();
        EnergyList = new ArrayList<>();
        ApproximationList = new double[Constants.NUM_HIST_POINTS + 1];
        coeffArray = null;
        Arrays.fill(ApproximationList, 0.0);
    }

    //copy constructor
    public Calibration(Calibration other) {
        ChannelList = new ArrayList<>();
        ChannelList.addAll(other.ChannelList);
        EnergyList = new ArrayList<>();
        EnergyList.addAll(other.EnergyList);
        ApproximationList = new double[Constants.NUM_HIST_POINTS+1];
        System.arraycopy(other.ApproximationList, 0, ApproximationList, 0, other.ApproximationList.length);
        if (other.coeffArray == null)
            coeffArray = null;
        else {
            coeffArray = new double[other.coeffArray.length];
            System.arraycopy(other.coeffArray, 0, coeffArray, 0, other.coeffArray.length);
        }
    }

    //clear all data from the instance
    public void clear() {
        ChannelList.clear();
        EnergyList.clear();
        coeffArray = null;
        Arrays.fill(ApproximationList, 0.0);
    }

    //add a new calibration line to the list, sort, and invalidate the calibration
    public Calibration addLine(int channel, double energy) {
        int pos = ChannelList.size();
        for (int i = 0; i < ChannelList.size(); i++) {
            if (channel < ChannelList.get(i)) {
                pos = i;
                break;
            }
        }

        ChannelList.add(pos, channel);
        EnergyList.add(pos, energy);
        coeffArray = null;
        return this;
    }

    //delete a new calibration line from the list, sort, and invalidate the calibration
    public Calibration removeLine(int number) {
        if (number >= 0 && number < ChannelList.size()) {
            ChannelList.remove(number);
            EnergyList.remove(number);
            coeffArray = null;
        }
        return this;
    }

    //computes polynomial coefficients and creates an approximation array
    public void Calculate() {
        if (isCorrectInternal()) {
            final int factor = ChannelList.size();
            coeffArray = new double[factor];
            double[][] matrix = new double[factor][factor + 1];
            //solve the polynomial coefficients using linear algebra:
            // M*X=Y
            // where M:
            // / 1.0   ...   x1^(n-1) \
            // | ...   ...   ...      |
            // \ 1.0   ...   xn^(n-1) /
            // X:
            // / x1  \
            // | ... |
            // \ xn  /
            // Y:
            // / y1  \
            // | ... |
            // \ yn  /
            //prepare matrix to calculate
            double x;

            for (int i = 0; i < factor; i++) {
                x = 1.0;
                for (int j = 0; j < factor; j++) {
                    matrix[i][j] = x;
                    x = x*ChannelList.get(i);
                }
                matrix[i][factor] = EnergyList.get(i);
            }

            //makes a calculation
            for (int i = 0; i < (factor - 1); i++) {
                double coeff;
                for (int j = i + 1; j < factor; j++) {
                    for (int k = i + 1; k <= factor; k++) {
                        matrix[j][k] -= matrix[i][k];
                    }
                    coeff = matrix[j][i+1];
                    for (int k = i + 1; k <= factor; k++) {
                        matrix[j][k] /= coeff;
                    }
                }
            }

            //back calculation
            for (int i = factor - 1; i >= 1; i--) {
                for (int j = i - 1; j >= 0; j--) {
                    matrix[j][factor] -= matrix[j][i]*matrix[i][factor];
                }
                coeffArray[i] = matrix[i][factor];
            }
            coeffArray[0] = matrix[0][factor];

            //from here we have all coefficients we need in a last column: matrix[end][0] + x*matrix[end][1] + ...
            //prepare approximation list using polynomial
            double coeff;
            for (int i = 0; i < Constants.NUM_HIST_POINTS+1; i++) {
                coeff = 1.0;
                x = 0.0;
                for (int j = 0; j < factor; j++) {
                    x += coeffArray[j]*coeff;
                    coeff *= i;
                }
                ApproximationList[i] = x;
            }

        } else {
            //Can't set calibration curve
            coeffArray = null;
            for(int i = 0; i < Constants.NUM_HIST_POINTS+1; i++)
                ApproximationList[i] = 0.0;
        }
    }

    //calculate new calibration array using function y=coeffs[0]+x*coeffs[1]+x^2*coeffs[2]+...
    public void Calculate(double[] coeffs) {
        int poli = coeffs.length;
        while (poli > 0 && coeffs[poli - 1] == 0) {
            poli--;
        }
        if (poli < 2) {
            coeffArray = null;
            Arrays.fill(ApproximationList, 0.0);
        } else {
            coeffArray = new double[poli];
            System.arraycopy(coeffs, 0, coeffArray, 0, poli);
            double coeff;
            double x;
            for (int i = 0; i < Constants.NUM_HIST_POINTS + 1; i++) {
                coeff = 1.0;
                x = 0.0;
                for (int j = 0; j < poli; j++) {
                    x += coeffArray[j] * coeff;
                    coeff *= i;
                }
                ApproximationList[i] = x;
            }
        }
    }

    //calculate using least squares method
    public void Calculate (int maxFactor) {
        if (maxFactor >= ChannelList.size() - 1) {
            Calculate();
            return;
        }
        coeffArray = null;
        if (!isCorrectInternal()) {
            //Can't set calibration curve
            for (int i = 0; i < Constants.NUM_HIST_POINTS + 1; i++)
                ApproximationList[i] = 0.0;
            return;
        }
        Matrix X_Vals = new Matrix(ChannelList.size(), maxFactor + 1);
        double x;
        for (int i = 0; i < ChannelList.size(); i++) {
            x = 1;
            for (int j = 0; j <= maxFactor; j++) {
                X_Vals.array[i][j] = x;
                x *= ChannelList.get(i);
            }
        }
        Matrix Y_Vals = new Matrix(ChannelList.size(), 1);
        for (int i = 0; i < EnergyList.size(); i++) {
            Y_Vals.array[i][0] = EnergyList.get(i);
        }
        Matrix b = X_Vals.Transpose().Times(X_Vals).Inverse().Times(X_Vals.Transpose()).Times(Y_Vals);
        double[] temp = new double[maxFactor + 1];
        for (int i = 0; i <= maxFactor; i++)
            temp[i] = b.array[i][0];
        Calculate(temp);
    }

    public Calibration approximateTo(int maxFactor) {
        if (getFactor() == 0)
            return null;
        if (maxFactor <= getFactor())
            return this;
        Calibration newCalibration = new Calibration();
        for (int i = 0; i < 20; i++) {
            newCalibration.addLine( i*400 + 50, ApproximationList[i*400 + 50]);
            newCalibration.Calculate(maxFactor);
        }
        if (newCalibration.isCorrect())
            return newCalibration;
        else
            return null;
    }

    public int getFactor() {
        return (coeffArray != null && (coeffArray.length > 1) ? (coeffArray.length - 1) : 0);
    }

    //factor of polynomial
    public int getLines(){
        return ChannelList.size();
    }

    //get channel of the calibration line
    public int getChannel(int i) {
        if (i >=0 && i < ChannelList.size())
            return (ChannelList.get(i));
        else
            return 0;
    }

    //get energy of the calibration line
    public double getEnergy(int i) {
        if (i >=0 && i < EnergyList.size())
            return EnergyList.get(i);
        else
            return 0.0;
    }

    //check if the line is already exists
    public boolean containsChannel(int channel) {
        for (Integer val : ChannelList) {
            if (val == channel)
                return true;
        }
        return false;
    }

    //get polynomial coefficients in a+bx+cx^2+dx^3+...
    public double[] getCoeffArray() {
        if (coeffArray == null)
            return null;

        double[] tmp = new double[coeffArray.length];
        System.arraycopy(coeffArray,0,tmp,0,coeffArray.length);
        return tmp;
    }

    public double[] getCoeffArray(int needed_size) {
        double[] tmp = new double[needed_size];
        if (coeffArray == null)
            return tmp;

        System.arraycopy(coeffArray,0,tmp,0,StrictMath.min(needed_size, coeffArray.length));
        return tmp;
    }

    public String getFunction() {
        if (coeffArray == null)
            return "";
        StringBuilder tmp = new StringBuilder("y=");
        String note;
        for (int i = 0; i < coeffArray.length; i++) {
            note = StrictMath.abs(coeffArray[i]) < 0.1 ? "e" : (StrictMath.abs(coeffArray[i]) > 1000.0 ? "e" : "f");
            tmp.append(String.format(Locale.US, i > 0 ? "%+.5" + note : "%.5" + note, coeffArray[i])).append(i > 1 ? String.format(Locale.US, "x^%d", i) : (i == 1 ? "x" : ""));
        }
        return tmp.toString();
    }

    //get the approximation list to show instead of channels
    public double[] getApproximationList(){
        double[] tmp = new double[ApproximationList.length];
        System.arraycopy(ApproximationList,0,tmp,0,ApproximationList.length);
        return tmp;
    }

    //check if data can be used
    public boolean isCorrect() {
        boolean result = (coeffArray != null) && (coeffArray.length > 1);
        if (result) {
            for (int i = 0; i < Constants.NUM_HIST_POINTS; i++) {
                if (ApproximationList[i] >= ApproximationList[i + 1]) {
                    result = false;
                    break;
                }
            }
        }
        return result;
    }

    //check if the data is correct for calculation
    private boolean isCorrectInternal(){
        boolean ret_value = false;
        if (ChannelList.size() > 1) {
            ret_value = true;
            for (int i = 1; i < ChannelList.size(); i++) {
                if (ChannelList.get(i - 1) >= ChannelList.get(i))
                    ret_value = false;
            }
            for (int i = 1; i < EnergyList.size(); i++) {
                if (EnergyList.get(i - 1) >= EnergyList.get(i))
                    ret_value = false;
            }
        }
        return ret_value;
    }

    //convert channel to energy
    double toEnergy(int channel) {
        if (channel < 0)
            return ApproximationList[0] - 1;
        if (channel > Constants.NUM_HIST_POINTS)
            return ApproximationList[Constants.NUM_HIST_POINTS] + 1;
        return ApproximationList[channel];
    }

    //convert to channel on energy scale
    int toEnergyChannel(int channel) {
        return (int)StrictMath.rint((ApproximationList[channel]-ApproximationList[0])/(ApproximationList[Constants.NUM_HIST_POINTS - 1] - ApproximationList[0])*(Constants.NUM_HIST_POINTS - 1));
    }

    int toEnergyChannel(double energy) {
        return (int)StrictMath.rint((energy-ApproximationList[0])/(ApproximationList[Constants.NUM_HIST_POINTS - 1] - ApproximationList[0])*(Constants.NUM_HIST_POINTS - 1));
    }

    double getEnergyFromEnergyChannel(int channel, int lastChannel) {
        return channel * (ApproximationList[lastChannel - 1] - ApproximationList[0]) / (Constants.NUM_HIST_POINTS - 1) + ApproximationList[0];
    }

    //get ordinal channel from energy scale channel
    double fromEnergyChannel(int energyChannel) {
        return (double) energyChannel /(Constants.NUM_HIST_POINTS - 1)*(ApproximationList[Constants.NUM_HIST_POINTS - 1] - ApproximationList[0]) + ApproximationList[0];
    }

    //convert all spectrum channels to energy
    double[] toEnergy(long[] spectrum, int lastChannel) {
        double[] tmp = new double[Constants.NUM_HIST_POINTS];
        double energy_l, energy_r;
        double coeff_e;
        coeff_e = (Constants.NUM_HIST_POINTS - 1) / (ApproximationList[lastChannel - 1] - ApproximationList[0]);
        double coeff;
        for (int i = 0; i < lastChannel; i++) {
            energy_l = (ApproximationList[i] - ApproximationList[0]) * coeff_e;
            energy_r = (ApproximationList[i + 1] - ApproximationList[0]) * coeff_e;
            coeff = spectrum[i] / (energy_r - energy_l);
            if (energy_r < 0)
                continue;
            if (energy_l < 0)
                energy_l = 0;
            if (energy_l > Constants.NUM_HIST_POINTS - 1)
                continue;
            if (energy_r >= Constants.NUM_HIST_POINTS - 1)
                energy_r = Constants.NUM_HIST_POINTS - 1;
            if (StrictMath.floor(energy_l) == StrictMath.floor(energy_r)) {
                tmp[(int) StrictMath.floor(energy_l)] += spectrum[i];
            } else {
                tmp[(int) StrictMath.floor(energy_l)] += coeff*(StrictMath.ceil(energy_l) - energy_l);
                for (int j = (int) StrictMath.ceil(energy_l); j < (int) StrictMath.floor(energy_r); j++) {
                    tmp[j] += coeff;
                }
                tmp[(int) StrictMath.floor(energy_r)] += coeff*(energy_r - StrictMath.floor(energy_r));
            }
        }
        return tmp;
    }

    //convert all spectrum channels to energy
    double[] toEnergy(double[] spectrum, int lastChannel) {
        double[] tmp = new double[Constants.NUM_HIST_POINTS];
        double energy_l, energy_r;
        double coeff_e;
        coeff_e = (Constants.NUM_HIST_POINTS - 1) / (ApproximationList[lastChannel - 1] - ApproximationList[0]);
        double coeff;
        for (int i = 0; i < lastChannel; i++) {
            energy_l = (ApproximationList[i] - ApproximationList[0]) * coeff_e;
            energy_r = (ApproximationList[i + 1] - ApproximationList[0]) * coeff_e;
            coeff = spectrum[i] / (energy_r - energy_l);
            if (energy_r < 0)
                continue;
            if (energy_l < 0)
                energy_l = 0;
            if (energy_l > Constants.NUM_HIST_POINTS - 1)
                continue;
            if (energy_r >= Constants.NUM_HIST_POINTS - 1)
                energy_r = Constants.NUM_HIST_POINTS - 1;
            if (StrictMath.floor(energy_l) == StrictMath.floor(energy_r)) {
                tmp[(int) StrictMath.floor(energy_l)] += spectrum[i];
            } else {
                tmp[(int) StrictMath.floor(energy_l)] += coeff*(StrictMath.ceil(energy_l) - energy_l);
                for (int j = (int) StrictMath.ceil(energy_l); j < (int) StrictMath.floor(energy_r); j++) {
                    tmp[j] += coeff;
                }
                tmp[(int) StrictMath.floor(energy_r)] += coeff*(energy_r - StrictMath.floor(energy_r));
            }
        }
        return tmp;
    }

    //convert all spectrum channels to energy
    double[] toEnergy(long[] spectrum, int adc_bits, int lastChannel) {
        return toEnergy(linearChannel(spectrum, adc_bits), lastChannel);
    }

    //convert all spectrum channels to energy
    double[] toEnergy(double[] spectrum, int adc_bits, int lastChannel) {
        return toEnergy(linearChannel(spectrum, adc_bits), lastChannel);
    }

    //convert all spectrum channels to energy
    double[] toEnergy(long[] spectrum, Calibration otherCalibration, int lastChannel) {
        double[] energies = otherCalibration.toEnergy(spectrum, lastChannel);
        //f=ax+b to convert from other energy to our energy
        //I shift data to another energy using linear function
        double e_l, e_m;
        e_l = (otherCalibration.ApproximationList[0]-ApproximationList[0]) / (ApproximationList[lastChannel - 1] - ApproximationList[0]) * (Constants.NUM_HIST_POINTS - 1);
        e_m = (otherCalibration.ApproximationList[lastChannel - 1] - ApproximationList[0]) / (ApproximationList[lastChannel - 1] - ApproximationList[0]) * (Constants.NUM_HIST_POINTS - 1);
        double a = (e_m - e_l)/(Constants.NUM_HIST_POINTS - 1);
        double b = e_l;
        double energy_l, energy_r;
        double coeff;
        double[] tmp = new double[Constants.NUM_HIST_POINTS];
        for(int i = 0; i < Constants.NUM_HIST_POINTS - 1; i++) {
            energy_l = a*i + b;
            energy_r = a*(i + 1) + b;
            coeff = energies[i]/(energy_r - energy_l);
            if(energy_l <= 0)
                energy_l = 0;
            if(energy_l > Constants.NUM_HIST_POINTS - 1)
                continue;
            if(energy_r < 0)
                continue;
            if(energy_r >= Constants.NUM_HIST_POINTS - 1)
                energy_r = Constants.NUM_HIST_POINTS - 1;
            if (StrictMath.floor(energy_l) == StrictMath.floor(energy_r)) {
                tmp[(int) StrictMath.floor(energy_l)] += energies[i];
            } else {
                tmp[(int) StrictMath.floor(energy_l)] += coeff*(StrictMath.ceil(energy_l)-energy_l);
                for(int j = (int) StrictMath.ceil(energy_l); j < (int) StrictMath.floor(energy_r); j++) {
                    tmp[j] += coeff;
                }
                tmp[(int) StrictMath.floor(energy_r)] += coeff*(energy_r - StrictMath.floor(energy_r));
            }
        }
        return tmp;
    }

    //convert all spectrum channels to energy
    double[] toEnergy(double[] spectrum, Calibration otherCalibration, int lastChannel) {
        double[] energies = otherCalibration.toEnergy(spectrum, lastChannel);
        //f=ax+b to convert from other energy to our energy
        //I shift data to another energy using linear function
        double e_l, e_m;
        e_l = (otherCalibration.ApproximationList[0]-ApproximationList[0]) / (ApproximationList[lastChannel - 1] - ApproximationList[0]) * (Constants.NUM_HIST_POINTS - 1);
        e_m = (otherCalibration.ApproximationList[lastChannel - 1] - ApproximationList[0]) / (ApproximationList[lastChannel - 1] - ApproximationList[0]) * (Constants.NUM_HIST_POINTS - 1);
        double a = (e_m - e_l)/(Constants.NUM_HIST_POINTS - 1);
        double b = e_l;
        double energy_l, energy_r;
        double coeff;
        double[] tmp = new double[Constants.NUM_HIST_POINTS];
        for(int i = 0; i < Constants.NUM_HIST_POINTS - 1; i++) {
            energy_l = a*i + b;
            energy_r = a*(i + 1) + b;
            coeff = energies[i]/(energy_r - energy_l);
            if(energy_l <= 0)
                energy_l = 0;
            if(energy_l > Constants.NUM_HIST_POINTS - 1)
                continue;
            if(energy_r < 0)
                continue;
            if(energy_r >= Constants.NUM_HIST_POINTS - 1)
                energy_r = Constants.NUM_HIST_POINTS - 1;
            if (StrictMath.floor(energy_l) == StrictMath.floor(energy_r)) {
                tmp[(int) StrictMath.floor(energy_l)] += energies[i];
            } else {
                tmp[(int) StrictMath.floor(energy_l)] += coeff*(StrictMath.ceil(energy_l)-energy_l);
                for(int j = (int) StrictMath.ceil(energy_l); j < (int) StrictMath.floor(energy_r); j++) {
                    tmp[j] += coeff;
                }
                tmp[(int) StrictMath.floor(energy_r)] += coeff*(energy_r - StrictMath.floor(energy_r));
            }
        }
        return tmp;
    }

    //convert all spectrum channels to energy
    double[] toEnergy(long[] spectrum, int adc_bits, Calibration otherCalibration, int lastChannel) {
        return toEnergy(linearChannel(spectrum, adc_bits), otherCalibration, lastChannel);
    }

    //convert all spectrum channels to energy
    double[] toEnergy(double[] spectrum, int adc_bits, Calibration otherCalibration, int lastChannel) {
        return toEnergy(linearChannel(spectrum, adc_bits), otherCalibration, lastChannel);
    }

    //convert energy to channel
    public int toChannel(double energy) {
        int delta = Constants.NUM_HIST_POINTS / 2;
        int begin = 0;
        if (energy < ApproximationList[0])
            return -1;
        if (energy > ApproximationList[Constants.NUM_HIST_POINTS - 1])
            return Constants.NUM_HIST_POINTS;
        while (delta > 0) {
            if (ApproximationList[begin + delta] < energy) {
                begin += delta;
            }
            delta = delta / 2;
        }
        return begin;
    }

    public double toChannelDouble(double energy, int lastChannel) {
        int delta = Constants.NUM_HIST_POINTS / 2;
        int begin = 0;
        if (energy < ApproximationList[0])
            return -1;
        if (energy > ApproximationList[Constants.NUM_HIST_POINTS - 1])
            return Constants.NUM_HIST_POINTS;
        while (delta > 0) {
            if (ApproximationList[begin + delta] < energy) {
                begin += delta;
            }
            delta = delta / 2;
        }
        if ((ApproximationList[begin] == energy) || (begin == (Constants.NUM_HIST_POINTS - 1)))
            return begin;
        else
            return begin + (energy-ApproximationList[begin])/(ApproximationList[begin + 1] - ApproximationList[begin]);
    }

    //convert all spectrum channels to energy
    public long[] toChannel(double[] spectrum) {
        double[] tmp = new double[Constants.NUM_HIST_POINTS];
        double energy_l, energy_r;
        int channel_l, channel_r;
        double coeff_e;
        coeff_e = (ApproximationList[Constants.NUM_HIST_POINTS - 1] - ApproximationList[0]) / (Constants.NUM_HIST_POINTS - 1);
        double coeff;
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i++) {
            channel_l = toChannel(ApproximationList[0] + i*coeff_e);
            channel_r = toChannel(ApproximationList[0] + (i+1)*coeff_e);
            if (channel_l >= Constants.NUM_HIST_POINTS)
                continue;
            if (channel_r < 0)
                continue;
            if (channel_l < 0)
                channel_l = 0;
            if (channel_r >= (Constants.NUM_HIST_POINTS - 1))
                channel_r = Constants.NUM_HIST_POINTS - 1;
            energy_l = channel_l + (ApproximationList[0] + i*coeff_e - ApproximationList[channel_l])/(ApproximationList[channel_l+1] - ApproximationList[channel_l]);
            energy_r = channel_r + (ApproximationList[0] + (i+1)*coeff_e - ApproximationList[channel_r])/(ApproximationList[channel_r+1] - ApproximationList[channel_r]);
            coeff = spectrum[i]/(energy_r - energy_l);
            if(energy_l > Constants.NUM_HIST_POINTS - 1)
                continue;
            if(energy_r >= Constants.NUM_HIST_POINTS - 1)
                energy_r = Constants.NUM_HIST_POINTS - 1;
            if (StrictMath.floor(energy_l) == StrictMath.floor(energy_r)) {
                tmp[(int) StrictMath.floor(energy_l)] += spectrum[i];
            } else {
                tmp[(int) StrictMath.floor(energy_l)] += coeff*(StrictMath.ceil(energy_l)-energy_l);
                for(int j = (int) StrictMath.ceil(energy_l); j < (int) StrictMath.floor(energy_r); j++) {
                    tmp[j] += coeff;
                }
                tmp[(int) StrictMath.floor(energy_r)] += coeff*(energy_r - StrictMath.floor(energy_r));
            }
        }
        long[] out = new long[Constants.NUM_HIST_POINTS];
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i++)
            out[i] = (long)StrictMath.rint(tmp[i]);
        return out;
    }

    //convert spectrum with another calibration data
    public long[] toChannel(long[] spectrum, Calibration otherCalibration, int lastChannel) {
        double[] energies = new double[Constants.NUM_HIST_POINTS];
        double channel_l, channel_r;
        double coeff;
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i++) {
            channel_l = toChannelDouble(otherCalibration.ApproximationList[i], lastChannel);
            channel_r = toChannelDouble(otherCalibration.ApproximationList[i + 1], lastChannel);
            if ((channel_r < 0) || (channel_l > (Constants.NUM_HIST_POINTS - 1)))
                continue;
            coeff = spectrum[i]/(channel_r - channel_l);
            if (channel_l < 0)
                channel_l = 0;
            if (channel_r > (Constants.NUM_HIST_POINTS - 1))
                channel_r = Constants.NUM_HIST_POINTS - 1;
            if (StrictMath.floor(channel_l) == StrictMath.floor(channel_r)) {
                energies[(int) StrictMath.floor(channel_l)] += spectrum[i];
            } else {
                energies[(int) StrictMath.floor(channel_l)] += coeff*(StrictMath.ceil(channel_l)-channel_l);
                for(int j = (int) StrictMath.ceil(channel_l); j < (int) StrictMath.floor(channel_r); j++) {
                    energies[j] += coeff;
                }
                energies[(int) StrictMath.floor(channel_r)] += coeff*(channel_r - StrictMath.floor(channel_r));
            }
        }
        long[] result = new long[Constants.NUM_HIST_POINTS];
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i++)
            result[i] = (long)energies[i];
        return result;
    }

    //convert spectrum with another calibration data
    public double[] toChannel(double[] spectrum, Calibration otherCalibration, int lastChannel) {
        double[] energies = new double[Constants.NUM_HIST_POINTS];
        double channel_l, channel_r;
        double coeff;
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i++) {
            channel_l = toChannelDouble(otherCalibration.ApproximationList[i], lastChannel);
            channel_r = toChannelDouble(otherCalibration.ApproximationList[i + 1], lastChannel);
            if ((channel_r < 0) || (channel_l > (Constants.NUM_HIST_POINTS - 1)))
                continue;
            coeff = spectrum[i]/(channel_r - channel_l);
            if (channel_l < 0)
                channel_l = 0;
            if (channel_r > (Constants.NUM_HIST_POINTS - 1))
                channel_r = Constants.NUM_HIST_POINTS - 1;
            if (StrictMath.floor(channel_l) == StrictMath.floor(channel_r)) {
                energies[(int) StrictMath.floor(channel_l)] += spectrum[i];
            } else {
                energies[(int) StrictMath.floor(channel_l)] += coeff*(StrictMath.ceil(channel_l)-channel_l);
                for(int j = (int) StrictMath.ceil(channel_l); j < (int) StrictMath.floor(channel_r); j++) {
                    energies[j] += coeff;
                }
                energies[(int) StrictMath.floor(channel_r)] += coeff*(channel_r - StrictMath.floor(channel_r));
            }
        }
        return energies;
    }

    //convert spectrum with another calibration data
    public long[] toChannel(long[] spectrum, int adc_bits, Calibration otherCalibration, int lastChannel) {
        return toChannel(linearChannel(spectrum, adc_bits), otherCalibration, lastChannel);
    }

    //convert spectrum with another calibration data
    public double[] toChannel(double[] spectrum, int adc_bits, Calibration otherCalibration, int lastChannel) {
        return toChannel(linearChannel(spectrum, adc_bits), otherCalibration, lastChannel);
    }

    public long[] linearChannel(long[] spectrum, int adc_bits) {
        long[] tmp_spectrum = new long[Constants.NUM_HIST_POINTS];
        long sum;
        int num_data = 1 << (Constants.ADC_MAX - adc_bits);
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i+= num_data) {
            sum = 0;
            for (int j = i; j < i + num_data; j++)
                sum += spectrum[j];
            sum /= num_data;
            for (int j = i; j < i + num_data; j++)
                tmp_spectrum[j] = sum;
        }
        return tmp_spectrum;
    }

    public double[] linearChannel(double[] spectrum, int adc_bits) {
        double[] tmp_spectrum = new double[Constants.NUM_HIST_POINTS];
        double sum;
        int num_data = 1 << (Constants.ADC_MAX - adc_bits);
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i+= num_data) {
            sum = 0;
            for (int j = i; j < i + num_data; j++)
                sum += spectrum[j];
            sum /= num_data;
            for (int j = i; j < i + num_data; j++)
                tmp_spectrum[j] = sum;
        }
        return tmp_spectrum;
    }

}

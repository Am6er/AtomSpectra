package org.fe57.atomspectra.data;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

public class Calibration {
    private final ArrayList<Integer> ChannelList;  //list of calibration channels
    private final ArrayList<Double> EnergyList;   //list of calibration energies in keV
    private @NonNull double[] ApproximationList;     //list of channel approximation energies
    private double[] coeffArray;            //polynomial coefficients array in f(x)=a+bx+cx^2+dx^3+...

    /**
     * Creates test good calibration
     * @return Calibration with 0 keV on the left side and 3 MeV on the right side.
     */
    public static Calibration defaultCalibration() {
        Calibration calibration = new Calibration().addLine(0, 0).addLine(Constants.NUM_HIST_POINTS - 1, 3000);
        calibration.Calculate();
        return calibration;
    }

    /**
     * Creates a test good calibration with desired number of channels
     * @param numChannels number of channels in a calibration
     * @return Calibration with 0 keV on the left side and 3 MeV on the right side.
     */
    public static Calibration defaultCalibration(int numChannels) {
        Calibration calibration = new Calibration().addLine(0, 0).addLine((Math.max(numChannels, 2)) - 1, 3000);
        calibration.Calculate();
        return calibration;
    }

    /**
     * Constructor with NUM_HIST_POINTS channels
     */
    public Calibration() {
        ChannelList = new ArrayList<>();
        EnergyList = new ArrayList<>();
        ApproximationList = new double[Constants.NUM_HIST_POINTS + 1];
        coeffArray = null;
        Arrays.fill(ApproximationList, 0.0);
    }

    /**
     * Constructor with desired number of channels
     * @param numChannels number of channels
     */
    public Calibration(int numChannels) {
        ChannelList = new ArrayList<>();
        EnergyList = new ArrayList<>();
        ApproximationList = new double[numChannels + 1];
        coeffArray = null;
        Arrays.fill(ApproximationList, 0.0);
    }

    /**
     * Copy constructor
     * @param other an instance to be copied
     */
    public Calibration(Calibration other) {
        ChannelList = new ArrayList<>();
        ChannelList.addAll(other.ChannelList);
        EnergyList = new ArrayList<>();
        EnergyList.addAll(other.EnergyList);
        ApproximationList = Arrays.copyOf(other.ApproximationList, other.ApproximationList.length);
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

    /**
     * Remove a calibration pair (channel, energy) from the list
     * @param number 0-based number of the pair
     * @return an object itself to use in chain commands
     */
    //delete a new calibration line from the list, sort, and invalidate the calibration
    public Calibration removeLine(int number) {
        if (number >= 0 && number < ChannelList.size()) {
            ChannelList.remove(number);
            EnergyList.remove(number);
            coeffArray = null;
        }
        return this;
    }

    /**
     * Computes polynomial coefficients and creates an approximation array
     */
    public void Calculate() {
        if (isPointsCorrect()) {
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
            calculateApproximationList();
        } else {
            //Can't set calibration curve
            coeffArray = null;
            Arrays.fill(ApproximationList, 0.0);
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
            calculateApproximationList();
        }
    }

    //calculate using least squares method
    public void Calculate (int maxFactor) {
        if (maxFactor >= ChannelList.size() - 1) {
            Calculate();
            return;
        }
        coeffArray = null;
        if (!isPointsCorrect()) {
            //Can't set calibration curve
            for (int i = 0; i < Constants.NUM_HIST_POINTS + 1; i++)
                ApproximationList[i] = 0.0;
            return;
        }
        Matrix X_Values = new Matrix(ChannelList.size(), maxFactor + 1);
        double x;
        for (int i = 0; i < ChannelList.size(); i++) {
            x = 1;
            for (int j = 0; j <= maxFactor; j++) {
                X_Values.array[i][j] = x;
                x *= ChannelList.get(i);
            }
        }
        Matrix Y_Values = new Matrix(ChannelList.size(), 1);
        for (int i = 0; i < EnergyList.size(); i++) {
            Y_Values.array[i][0] = EnergyList.get(i);
        }
        Matrix b = X_Values.Transpose().Times(X_Values).Inverse().Times(X_Values.Transpose()).Times(Y_Values);
        double[] temp = new double[maxFactor + 1];
        for (int i = 0; i <= maxFactor; i++)
            temp[i] = b.array[i][0];
        Calculate(temp);
    }

    public boolean setChannels(int channels) {
        if (channels < Constants.MIN_NUM_HIST_POINTS)
            return false;

        ApproximationList = new double[channels + 1];
        calculateApproximationList();
        return true;
    }

    private void calculateApproximationList() {
        double coeff, x;
        for (int i = 0; i < ApproximationList.length; i++) {
            coeff = 1.0;
            x = 0.0;
            for (double v : coeffArray) {
                x += v * coeff;
                coeff *= i;
            }
            ApproximationList[i] = x;
        }
    }

    public Calibration approximateTo(int maxFactor) {
        if (getFactor() < 1)
            return null;
        if (maxFactor <= getFactor())
            return this;
        Calibration newCalibration = new Calibration();
        for (int i = 0; i < 21; i++) {
            newCalibration.addLine( i*ApproximationList.length/20, ApproximationList[i*ApproximationList.length/20]);
        }
        newCalibration.Calculate(maxFactor);
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

    /**
     * @return a copy of approximation list
     */
    public double[] getApproximationList(){
        double[] tmp = new double[ApproximationList.length];
        System.arraycopy(ApproximationList,0,tmp,0,ApproximationList.length);
        return tmp;
    }

    /**
     * Checks if the calibration data is correct
     * @return true if calibration is good and false otherwise
     */
    public boolean isCorrect() {
        boolean result = (coeffArray != null) && (coeffArray.length > Constants.MIN_NUM_HIST_POINTS);
        if (result) {
            for (int i = 0; i < ApproximationList.length - 1; i++) {
                if (ApproximationList[i] >= ApproximationList[i + 1]) {
                    result = false;
                    break;
                }
            }
        }
        return result;
    }

    /**
     * @return true if calibration points are ready to be used in calibration
     */
    private boolean isPointsCorrect(){
        if (ChannelList.size() < 2) {
            return false;
        }
        boolean ret_value = true;
        for (int i = 1; i < ChannelList.size() && ret_value; i++) {
            if (ChannelList.get(i - 1) >= ChannelList.get(i))
                ret_value = false;
        }
        for (int i = 1; i < EnergyList.size() && ret_value; i++) {
            if (EnergyList.get(i - 1) >= EnergyList.get(i))
                ret_value = false;
        }
        return ret_value;
    }

    /**
     * Receives channel number and returns an energy value.
     * @param channel Channel number
     * @return Energy corresponding to the channel number
     */
    public double toEnergy(int channel) {
        if (channel < 0)
            return ApproximationList[0] - 1;
        if (channel >= ApproximationList.length)
            return ApproximationList[ApproximationList.length - 1] + 1;
        return ApproximationList[channel];
    }

    /**
     * @param channel Channel number on a linear energy scale
     * @param lastChannel Last energy channel number to be used
     * @return Energy value
     * @deprecated
     */
    public double getEnergyFromEnergyChannel(int channel, int lastChannel) {
        return (double) channel * (ApproximationList[lastChannel - 1] - ApproximationList[0]) / (ApproximationList.length - 2) + ApproximationList[0];
    }

    //convert all spectrum channels to energy
    public double[] toEnergy(long[] spectrum, int lastChannel) {
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

    /**
     * Converts all spectrum channels to energy
     * @param spectrum All spectrum channels as is
     * @param lastChannel The last energy channel to be shown
     * @return Spectrum channels if the energy calibration is linear and cut by lastChannel
     */
    public double[] toEnergy(double[] spectrum, int lastChannel) {
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

    /**
     * Converts all spectrum channels to energy
     * @param spectrum All spectrum channels as is
     * @param adc_bits
     * @param lastChannel The last energy channel to be shown
     * @return Spectrum channels if the energy calibration is linear and cut by lastChannel
     * @return
     */
    //convert all spectrum channels to energy
    public double[] toEnergy(long[] spectrum, int adc_bits, int lastChannel) {
        return toEnergy(linearChannel(spectrum, adc_bits), lastChannel);
    }

    //convert all spectrum channels to energy
    public double[] toEnergy(double[] spectrum, int adc_bits, int lastChannel) {
        return toEnergy(linearChannel(spectrum, adc_bits), lastChannel);
    }

    //convert all spectrum channels to energy
    public double[] toEnergy(long[] spectrum, Calibration otherCalibration, int lastChannel) {
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
    public double[] toEnergy(double[] spectrum, Calibration otherCalibration, int lastChannel) {
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
    public double[] toEnergy(long[] spectrum, int adc_bits, Calibration otherCalibration, int lastChannel) {
        return toEnergy(linearChannel(spectrum, adc_bits), otherCalibration, lastChannel);
    }

    //convert all spectrum channels to energy
    public double[] toEnergy(double[] spectrum, int adc_bits, Calibration otherCalibration, int lastChannel) {
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

    /**
     * Reduce number of channels to NUM_HIST_POINTS channels
     * @param spectrum Source spectrum
     * @param adc_bits Number of bits of the resulted spectrum
     * @return Reduced spectrum
     */
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

    /**
     * Reduce number of channels to NUM_HIST_POINTS channels
     * @param spectrum Source spectrum
     * @param adc_bits Number of bits of the resulted spectrum
     * @return Reduced spectrum
     */
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

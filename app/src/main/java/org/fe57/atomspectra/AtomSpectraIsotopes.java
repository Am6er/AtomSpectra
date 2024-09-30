package org.fe57.atomspectra;

import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;

public class AtomSpectraIsotopes extends Activity implements AdapterView.OnItemSelectedListener {
    public static final String ISOTOPE_CHAIN_STATE = "Isotope chain ";   //save chains
    public static final String ISOTOPE_STATE = "Isotope ";               //save isotope energies state
    public static final String ISOTOPE_LINE_STATE = "Isotope energy ";   //save isotope state

    private static final IsotopeList fullIsotopeList = new IsotopeList().
            add(new Isotope("Pu-239", Isotope.Years(24410)).
                    addEnergy(  12.98, 0.0341).
                    addEnergy(  38.66,   0.0104).
                    addEnergy(  51.62,   0.0272)).
            add(new Isotope("Co-57",  Isotope.Days(271.74)).
                    addEnergy(  14.41,   9.18).
                    addEnergy( 122.06,  85.49).
                    addEnergy( 136.47,  10.71)).
            add(new Isotope("Pd-103", Isotope.Days(16.991)).
                    addEnergy(  20.22,  41.83)).
            add(new Isotope("In-111", Isotope.Days(2.8047)).
                    addEnergy(  23.17,  44.47).
                    addEnergy( 171.28,  90.61).
                    addEnergy( 245.35,  94.12)).
            add(new Isotope("Th-231", Isotope.Hours(25.52)).
                    addEnergy(  25.64,  13.90)).
            add(new Isotope("Am-241", Isotope.Years(432.6)).
                    addEnergy(  26.34,   2.31).
                    addEnergy(  59.54,  35.92)).
            add(new Isotope("Pa-231", Isotope.Years(3.276e4)).
                    addEnergy(  27.37,  10.80).
                    addEnergy( 300.06,   2.41).
                    addEnergy( 302.67,   2.30)).
            add(new Isotope("I-125",  Isotope.Days(59.4)).
                    addEnergy(  27.47,  73.20).
                    addEnergy(  35.49,   6.63)).
            add(new Isotope("Np-237", Isotope.Years(2.144e6)).
                    addEnergy(  29.37,  14.30).
                    addEnergy(  86.48,  12.26)).
            add(new Isotope("Ba-140", Isotope.Days(12.751)).
                    addEnergy(  29.96,  14.40).
                    addEnergy( 162.66,   6.49).
                    addEnergy( 537.26,  24.60)).
            add(new Isotope("Xe-133", Isotope.Days(5.25)).
                    addEnergy(  30.97,  25.00).
                    addEnergy(  81.00,  37.00)).
            add(new Isotope("Sb-125", Isotope.Years(2.759)).
                    addEnergy(  35.49,   5.79).
                    addEnergy( 176.31,   6.82).
                    addEnergy( 427.87,  29.55).
                    addEnergy( 463.37,  10.48).
                    addEnergy( 600.60,  17.76).
                    addEnergy( 606.71,   5.02).
                    addEnergy( 635.95,  11.32)).
            add(new Isotope("I-129",  Isotope.Years(1.57e7)).
                    addEnergy(  39.58,   7.42)).
            add(new Isotope("Bi-212", Isotope.Minutes(60.55)).
                    addEnergy(  39.85,   1.06).
                    addEnergy( 727.33,   6.65).
                    addEnergy( 785.35,   1.10).
                    addEnergy( 893.41,   0.378).
                    addEnergy( 959.12,   0.17).
                    addEnergy(1078.62,   0.564).
                    addEnergy(1512.70,   0.29).
                    addEnergy(1620.74,   1.51)).
            add(new Isotope("Ra-225", Isotope.Days(14.9)).
                    addEnergy(  40.09,  30.00)).
            add(new Isotope("Mo-99",  Isotope.Hours(65.92)).
                    addEnergy(  40.58,   1.02).
                    addEnergy( 140.51,  89.60).
                    addEnergy( 181.09,   6.01).
                    addEnergy( 366.42,   1.19).
                    addEnergy( 739.50,  12.12).
                    addEnergy( 777.92,   4.28)).
            add(new Isotope("Sm-153", Isotope.Hours(46.284)).
                    addEnergy(  41.54,  30.00).
                    addEnergy(  69.67,   4.69).
                    addEnergy( 103.18,  29.19)).
            add(new Isotope("U-233",  Isotope.Years(1.592e5)).
                    addEnergy(  42.43,   0.07)).
            add(new Isotope("Am-243", Isotope.Years(7364)).
                    addEnergy(  43.53,   5.89).
                    addEnergy(  74.66,  67.20)).
            add(new Isotope("Pu-240", Isotope.Years(6561)).
                    addEnergy(  45.24,   0.0447).
                    addEnergy( 104.23,   0.0071)).
            add(new Isotope("Pb-210", Isotope.Years(22.2)).
                    addEnergy(  46.54,   4.25)).
            add(new Isotope("U-238",  Isotope.Years(4.468e9)).
                    addEnergy(  49.55,   0.064).
                    addEnergy( 113.50,   0.0102)).
            add(new Isotope("Te-132", Isotope.Days(3.204)).
                    addEnergy(  49.72,  15.10).
                    addEnergy( 228.33,  88.12)).
            add(new Isotope("Fr-223", Isotope.Minutes(22)).
                    addEnergy(  50.10,  33.00).
                    addEnergy(  79.65,   9.00)).
            add(new Isotope("Th-227", Isotope.Days(18.697)).
                    addEnergy(  50.13,   8.40).
                    addEnergy( 235.96,  12.9).
                    addEnergy( 256.23,   7.00)).
            add(new Isotope("U-234",  Isotope.Years(2.455e5)).
                    addEnergy(  52.20,   0.123).
                    addEnergy( 120.90,   0.0342)).
            add(new Isotope("Pb-214", Isotope.Minutes(27.06)).
                    addEnergy(  53.23,   1.075).
                    addEnergy( 242.00,   7.27).
                    addEnergy( 295.22,  18.41).
                    addEnergy( 351.93,  35.60).
                    addEnergy( 785.96,   1.06)).
            add(new Isotope("U-237",  Isotope.Days(6.752)).
                    addEnergy(  59.54,  34.10).
                    addEnergy( 208.00,  21.30)).
            add(new Isotope("Yb-169", Isotope.Days(32.018)).
                    addEnergy(  63.12,   44.05).
                    addEnergy( 109.78,  17.36).
                    addEnergy( 130.52,  11.38).
                    addEnergy( 177.21,  22.32).
                    addEnergy( 197.96,  35.93).
                    addEnergy( 307.74,  10.05)).
            add(new Isotope("Th-234", Isotope.Days(24.1)).
                    addEnergy(  63.30,   3.75).
                    addEnergy(  92.38,   2.13).
                    addEnergy(  92.80,   2.10).
                    addEnergy( 112.81,   0.21)).
            add(new Isotope("Th-232", Isotope.Years(1.4e10)).
                    addEnergy(  63.81,   0.26).
                    addEnergy( 140.88,   0.021)).
            add(new Isotope("Se-75", Isotope.Days(119.78)).
                    addEnergy(  66.05,   1.09).
                    addEnergy(  96.73,   3.35).
                    addEnergy( 121.12,  16.86).
                    addEnergy( 136.00,  57.70).
                    addEnergy( 198.61,   1.46).
                    addEnergy( 264.66,  58.75).
                    addEnergy( 279.54,  24.89).
                    addEnergy( 400.66,  11.39)).
            add(new Isotope("Th-230", Isotope.Years(7.54e4)).
                    addEnergy(  67.67,   0.38).
                    addEnergy( 143.87,   0.049)).
            add(new Isotope("Ta-182", Isotope.Days(114.74)).
                    addEnergy(  67.75,  43.60).
                    addEnergy( 100.11,  14.22).
                    addEnergy( 152.43,   7.01).
                    addEnergy( 222.11,   7.54).
                    addEnergy(1121.29,  35.17).
                    addEnergy(1189.04,  16.58).
                    addEnergy(1221.40,  27.27).
                    addEnergy(1231.00,  11.62)).
            add(new Isotope("Ti-44", Isotope.Years(59.1)).
                    addEnergy(  67.87,  93.00).
                    addEnergy(  78.36,  96.40)).
            add(new Isotope("Tl-201", Isotope.Days(3.042)).
                    addEnergy(  70.82,  46.40).
                    addEnergy( 135.31,   2.60).
                    addEnergy( 167.45,  10.00)).
            add(new Isotope("U-239", Isotope.Minutes(23.45)).
                    addEnergy(  74.66,  51.60)).
            add(new Isotope("Ag-108m", Isotope.Years(438)).
                    addEnergy(  79.13,   6.90).
                    addEnergy( 433.94,  90.10).
                    addEnergy( 614.28,  90.50).
                    addEnergy( 722.90,  90.80)).
            add(new Isotope("I-131", Isotope.Days(8.052)).
                    addEnergy(  80.19,   2.61).
                    addEnergy( 284.31,   6.14).
                    addEnergy( 364.49,  81.20).
                    addEnergy( 636.99,   7.12).
                    addEnergy( 722.91,   1.79)).
            add(new Isotope("Ho-166", Isotope.Hours(26.824)).
                    addEnergy(  80.57,   6.55)).
            add(new Isotope("Ho-166m", Isotope.Years(1133)).
                    addEnergy(  80.57,  12.66).
                    addEnergy( 184.41,  72.50).
                    addEnergy( 280.46,  29.54).
                    addEnergy( 529.83,   9.40).
                    addEnergy( 571.00,   5.43).
                    addEnergy( 711.70,  54.90).
                    addEnergy( 752.28,  12.20).
                    addEnergy( 810.29,  57.30).
                    addEnergy( 830.57,   9.72)).
            add(new Isotope("Ba-133", Isotope.Years(10.551)).
                    addEnergy(  81.00,  33.31).
                    addEnergy( 276.40,   7.13).
                    addEnergy( 302.85,  18.31).
                    addEnergy( 356.01,  62.05).
                    addEnergy( 383.85,   8.94)).
            add(new Isotope("Th-231", Isotope.Hours(25.52)).
                    addEnergy(  84.21,   6.70)).
            add(new Isotope("Th-228", Isotope.Years(1.912)).
                    addEnergy(  84.38,   1.19)).
            add(new Isotope("Eu-155", Isotope.Years(4.753)).
                    addEnergy(  86.55,  30.70).
                    addEnergy( 105.31,  21.10)).
            add(new Isotope("Cd-109", Isotope.Days(461.9)).
                    addEnergy(  88.03,   3.64)).
            add(new Isotope("Lu-176", Isotope.Years(3.76e10)).
                    addEnergy(  88.34,  14.50).
                    addEnergy( 201.83,  78.00).
                    addEnergy( 306.78,  93.60)).
            add(new Isotope("Nd-147", Isotope.Days(10.98)).
                    addEnergy(  91.11,  28.40).
                    addEnergy( 531.02,  12.70)).
            add(new Isotope("Ga-67", Isotope.Days(3.2617)).
                    addEnergy(  91.26,   3.09).
                    addEnergy(  93.31,  38.10).
                    addEnergy( 184.58,  20.96).
                    addEnergy( 208.94,   2.37).
                    addEnergy( 300.23,  16.60).
                    addEnergy( 393.53,   4.59)).
            add(new Isotope("Gd-153", Isotope.Days(240.4)).
                    addEnergy(  97.43,  29.00).
                    addEnergy( 103.18,  21.10)).
            add(new Isotope("Au-195", Isotope.Days(186.01)).
                    addEnergy(  98.88,  11.21)).
            add(new Isotope("Np-236", Isotope.Years(153e3)).
                    addEnergy( 102.82,   0.91).
                    addEnergy( 104.23,   7.32).
                    addEnergy( 158.35,   4.20).
                    addEnergy( 160.31,  31.30)).
            add(new Isotope("Np-239", Isotope.Days(2.356)).
                    addEnergy( 106.13,  25.90).
                    addEnergy( 228.18,  11.32).
                    addEnergy( 277.60,  14.40)).
            add(new Isotope("Lu-177", Isotope.Days(6.6443)).
                    addEnergy( 112.95,   6.20).
                    addEnergy( 208.37,  10.38)).
            add( new Isotope("Tl-209", Isotope.Minutes(2.162)).
                    addEnergy( 117.22,  77.22).
                    addEnergy( 465.13,  99.62).
                    addEnergy(1566.93,  99.71)).
            add(new Isotope("Eu-152", Isotope.Years(13.517)).
                    addEnergy( 121.78,  28.41).
                    addEnergy( 244.70,   7.55).
                    addEnergy( 344.28,  26.59).
                    addEnergy( 778.91,  12.97).
                    addEnergy( 964.08,  14.50).
                    addEnergy(1085.84,  10.13).
                    addEnergy(1112.08,  13.41).
                    addEnergy(1408.01,  20.85)).
            add(new Isotope("Eu-154", Isotope.Years(8.601)).
                    addEnergy( 123.08,  40.40).
                    addEnergy( 247.93,   6.89).
                    addEnergy( 723.30,  20.05).
                    addEnergy( 873.18,  12.17).
                    addEnergy( 996.25,  10.50).
                    addEnergy(1004.72,  17.86).
                    addEnergy(1274.43,  34.90)).
            add(new Isotope("Pa-234", Isotope.Hours(6.70)).
                    addEnergy( 131.30,  18.20).
                    addEnergy( 569.50,   8.60).
                    addEnergy( 883.24,   9.70).
                    addEnergy( 925.00,   7.90).
                    addEnergy( 926.70,   7.30).
                    addEnergy( 946.00,  13.50)).
            add(new Isotope("Pa-234m", Isotope.Minutes(1.159)).
                    addEnergy(1001.03, 0.842).
                    addEnergy( 766.42, 0.317).
                    addEnergy( 742.81, 0.107).
                    addEnergy( 258.23, 0.0764).
                    addEnergy( 786.28, 0.0544).
                    addEnergy(1737.75, 0.0213).
                    addEnergy(  73.92, 0.013)).
            add(new Isotope("Ce-144", Isotope.Days(284.91)).
                    addEnergy( 133.52,  10.83)).
            add(new Isotope("Tc-99m", Isotope.Hours(6)).
                    addEnergy( 140.51,  89.60).
                    addEnergy( 142.63, 0.0222)).
            add(new Isotope("U-235", Isotope.Years(7.04e8)).
                    addEnergy( 143.77,  10.94).
                    addEnergy( 163.36,   5.08).
                    addEnergy( 185.72,  57.00).
                    addEnergy( 205.32,   5.02)).
            add(new Isotope("Ce-141", Isotope.Days(32.511)).
                    addEnergy( 145.44,  48.29)).
            add(new Isotope("Ra-223", Isotope.Days(11.43)).
                    addEnergy( 154.21,   5.84).
                    addEnergy( 269.46,  14.23)).
            add(new Isotope("I-123", Isotope.Hours(13.22)).
                    addEnergy( 158.97,  83.25).
                    addEnergy( 528.96,   1.28)).
            add(new Isotope("Te-123m", Isotope.Days(119.2)).
                    addEnergy( 158.97,  83.99)).
            add(new Isotope("Ra-226", Isotope.Years(1600)).
                    addEnergy( 186.21,   3.55)).
            add(new Isotope("Ir-192", Isotope.Days(73.83)).
                    addEnergy( 205.79,   3.34).
                    addEnergy( 295.96,  28.72).
                    addEnergy( 308.46,  29.68).
                    addEnergy( 316.51,  82.75).
                    addEnergy( 468.07,  47.81).
                    addEnergy( 604.41,   8.20).
                    addEnergy( 612.46,   5.34)).
            add(new Isotope("Ac-228", Isotope.Hours(6.15)).
                    addEnergy( 209.25,   3.97).
                    addEnergy( 270.27,   3.55).
                    addEnergy( 328.00,   3.04).
                    addEnergy( 338.37,  11.40).
                    addEnergy( 463.00,   4.45).
                    addEnergy( 794.94,   4.31).
                    addEnergy( 911.20,  26.20).
                    addEnergy( 964.79,   4.99).
                    addEnergy( 968.96,  15.90).
                    addEnergy(1588.20,   3.06)).
            add(new Isotope("Fr-221", Isotope.Minutes(4.9)).
                    addEnergy( 218.12,  11.42)).
            add(new Isotope("Nb-95m", Isotope.Days(3.61)).
                    addEnergy( 235.69,  25.10)).
            add(new Isotope("Pb-212", Isotope.Hours(10.64)).
                    addEnergy( 238.63,  43.60).
                    addEnergy( 300.09,   3.18)).
            add(new Isotope("Ra-224", Isotope.Days(3.63)).
                    addEnergy( 240.97,   4.12)).
            add(new Isotope("Sb-127", Isotope.Days(3.85)).
                    addEnergy( 252.64,   8.28).
                    addEnergy( 473.26,  24.80).
                    addEnergy( 685.09,  35.40).
                    addEnergy( 782.60,  14.70)).
            add(new Isotope("Rn-219", Isotope.Seconds(3.96)).
                    addEnergy( 271.23,  11.07).
                    addEnergy( 401.81,   6.75)).
            add(new Isotope("Tl-208", Isotope.Minutes(3.053)).
                    addEnergy( 277.36,   6.60).
                    addEnergy( 510.74,  22.50).
                    addEnergy( 583.19,  85.00).
                    addEnergy( 860.53,  12.40).
                    addEnergy(2614.51,  99.76)).
            add(new Isotope("Hg-203", Isotope.Days(46.594)).
                    addEnergy( 279.20,  81.48)).
            add(new Isotope("Bi-215", Isotope.Minutes(7.6)).
                    addEnergy( 293.56,  23.80)).
            add(new Isotope("Tl-210", Isotope.Minutes(1.3)).
                    addEnergy( 296.00,  79.00).
                    addEnergy( 799.60,  99.97).
                    addEnergy( 860.00,   6.90).
                    addEnergy(1070.00,  11.90).
                    addEnergy(1110.00,   6.90).
                    addEnergy(1210.00,  16.80).
                    addEnergy(1310.00,  20.80).
                    addEnergy(2010.00,   6.90).
                    addEnergy(2360.00,   7.90).
                    addEnergy(2430.00,   8.90)).
            add(new Isotope("Pa-233", Isotope.Days(26.975)).
                    addEnergy( 300.13,   6.60).
                    addEnergy( 311.90,  38.30)).
            add(new Isotope("Cr-51", Isotope.Days(27.704)).
                    addEnergy( 320.08,   9.89)).
            add(new Isotope("Bi-211", Isotope.Minutes(2.14)).
                    addEnergy( 351.03,  13.00)).
            add(new Isotope("Pb-211", Isotope.Minutes(36.1)).
                    addEnergy( 404.83,   3.83).
                    addEnergy( 831.98,   3.50)).
            add(new Isotope("Bi-213", Isotope.Minutes(45.59)).
                    addEnergy( 440.44,  26.10)).
            add(new Isotope("N-13", Isotope.Minutes(9.965)).
                    addEnergy( 511.00, 199.64)).
            add(new Isotope("F-18", Isotope.Minutes(109.77)).
                    addEnergy( 511.00, 193.72)).
            add(new Isotope("Na-22", Isotope.Years(2.6018)).
                    addEnergy( 511.00, 180.70).
                    addEnergy(1274.58,  99.94)).
            add(new Isotope("Al-26", Isotope.Years(7.17e5)).
                    addEnergy( 511.00, 163.50)).
            add(new Isotope("Co-56", Isotope.Days(271.74)).
                    addEnergy( 511.00,  39.21).
                    addEnergy( 846.76,  99.94).
                    addEnergy(1037.84,  14.03).
                    addEnergy(1238.27,  66.41).
                    addEnergy(1771.33,  15.45).
                    addEnergy(2034.75,   7.74).
                    addEnergy(2598.44,  16.96).
                    addEnergy(3253.40,   7.87)).
            add(new Isotope("Sr-85", Isotope.Days(64.849)).
                    addEnergy( 514.00,  98.50)).
            add(new Isotope("Cs-134", Isotope.Years(2.0652)).
                    addEnergy( 563.25,   8.34).
                    addEnergy( 569.33,  15.37).
                    addEnergy( 604.72,  97.63).
                    addEnergy( 795.86,  85.47).
                    addEnergy( 801.95,   8.69)).
            add(new Isotope("Bi-207", Isotope.Years(31.55)).
                    addEnergy( 569.70,  97.76).
                    addEnergy(1063.66,  74.58).
                    addEnergy(1770.23,   6.87)).
            add(new Isotope("Bi-214", Isotope.Minutes(19.9)).
                    addEnergy( 609.31,  45.49).
                    addEnergy( 768.36,   4.90).
                    addEnergy( 934.06,   3.11).
                    addEnergy(1120.29,  14.91).
                    addEnergy(1238.11,   5.83).
                    addEnergy(1377.67,   3.99).
                    addEnergy(1407.99,   2.39).
                    addEnergy(1729.60,   2.88).
                    addEnergy(1764.49,  15.31).
                    addEnergy(2204.21,   4.91)).
            add(new Isotope("Ag-110m", Isotope.Days(249.83)).
                    addEnergy( 657.76,  94.38).
                    addEnergy( 677.62,  10.56).
                    addEnergy( 687.01,   6.45).
                    addEnergy( 706.68,  16.48).
                    addEnergy( 763.94,  22.31).
                    addEnergy( 818.02,   7.33).
                    addEnergy( 884.68,  74.00).
                    addEnergy( 937.49,  34.51).
                    addEnergy(1384.29,  24.7).
                    addEnergy(1505.03,  13.16)).
            add(new Isotope("Cs-137", Isotope.Years(30.08)).
                    addEnergy(  31.82,   1.99).
                    addEnergy(  32.19,   3.67).
                    addEnergy(  36.80,   1.35).
                    addEnergy( 661.66,  84.99)).
            add(new Isotope("Nb-95", Isotope.Days(34.991)).
                    addEnergy( 765.80,  99.81)).
            add(new Isotope("La-138", Isotope.Years(1.03e11)).
                    addEnergy( 788.74,  34.70).
                    addEnergy(1435.80,  65.10)).
            add(new Isotope("Mn-54", Isotope.Days(312.20)).
                    addEnergy( 834.85,  99.98)).
            add(new Isotope("Sc-46", Isotope.Days(83.79)).
                    addEnergy( 889.27,  99.98).
                    addEnergy(1120.55,  99.98)).
            add(new Isotope("Y-88", Isotope.Days(106.626)).
                    addEnergy( 898.04,  93.70).
                    addEnergy(1836.07,  99.35)).
            add(new Isotope("Sr-89", Isotope.Days(50.563)).
                    addEnergy( 908.96,  0.00964)).
            add(new Isotope("Np-238", Isotope.Days(2.099)).
                    addEnergy( 984.45,  25.18).
                    addEnergy(1025.87,   8.76).
                    addEnergy(1028.54,  18.25)).
            add(new Isotope("Zn-65", Isotope.Days(243.93)).
                    addEnergy(1115.54,  50.22)).
            add(new Isotope("Co-60", Isotope.Days(1925.28)).
                    addEnergy(1173.23,  99.85).
                    addEnergy(1332.49,  99.98)).
            add(new Isotope("K-40",Isotope.Years(1.248e9)).
                    addEnergy(1460.82,  10.55)).
            add(new Isotope("Al-26", Isotope.Years(7.17e5)).
                    addEnergy(1808.65,  99.76)).
            add(new Isotope("N-16", Isotope.Seconds(7.16)).
                    addEnergy(6128.63,  67.00).
                    addEnergy(7115.15,   4.90));

    //Isotopes must be ordered according to energies for searching
    public static ArrayList<Isotope> isotopeLineArray = fullIsotopeList.getLinesList();
    public static boolean[] checkedIsotopeLine = new boolean[isotopeLineArray.size()];
    //Decay chains
    public static Isotope[] decayChains = {
            new Isotope("Th-232", Isotope.ChainSign).
                    addToChain("Ra-228").
                    addToChain("Ac-228").
                    addToChain("Th-228").
                    addToChain("Ra-224").
                    addToChain("Rn-220").
                    addToChain("Po-216").
                    addToChain("Pb-212").
                    addToChain("Bi-212").
                    addToChain("Po-212").
                    addToChain("Tl-208"),
            new Isotope("Pu-241", Isotope.ChainSign).
                    addToChain("Am-241").
                    addToChain("Np-237").
                    addToChain("Pa-233").
                    addToChain("U-233").
                    addToChain("Th-229").
                    addToChain("Ra-225").
                    addToChain("Ac-225").
                    addToChain("Fr-221").
                    addToChain("At-217").
                    addToChain("Bi-213").
                    addToChain("Po-213").
                    addToChain("Tl-209").
                    addToChain("Pb-209").
                    addToChain("Bi-209"),
            new Isotope("U-238", Isotope.ChainSign).
                    addToChain("Th-234").
                    addToChain("Pa-234m").
                    addToChain("U-234").
                    addToChain("Th-230").
                    addToChain("Ra-226").
                    addToChain("Rn-222").
                    addToChain("Po-218").
                    addToChain("At-218").
                    addToChain("Rn-218").
                    addToChain("Pb-214").
                    addToChain("Bi-214").
                    addToChain("Po-214").
                    addToChain("Tl-210").
                    addToChain("Pb-210").
                    addToChain("Bi-210").
                    addToChain("Po-210").
                    addToChain("Tl-206"),
            new Isotope("Pu-239", Isotope.ChainSign).
                    addToChain("U-235").
                    addToChain("Th-231").
                    addToChain("Pa-231").
                    addToChain("Ac-227").
                    addToChain("Th-227").
                    addToChain("Fr-223").
                    addToChain("Ra-223").
                    addToChain("At-219").
                    addToChain("Rn-219").
                    addToChain("Bi-215").
                    addToChain("Po-215").
                    addToChain("At-215").
                    addToChain("Pb-211").
                    addToChain("Bi-211").
                    addToChain("Po-211").
                    addToChain("Tl-207")
    };

    //IAEA list of isotopes to be detected
    public static Isotope[] IAEAList = {
            //Medicine
            new Isotope("F-18", Isotope.ChainSign).
                    addToChain("Ga-67").
                    addToChain("Cr-51").
                    addToChain("Se-75").
                    addToChain("Sr-89").
                    addToChain("Mo-99").
                    addToChain("Tc-99m").
                    addToChain("Pd-103").
                    addToChain("In-111").
                    addToChain("I-123").
                    addToChain("I-125").
                    addToChain("I-131").
                    addToChain("Sm-153").
                    addToChain("Tl-201").
                    addToChain("Xe-133"),
            //Industrial
            new Isotope("Co-57", Isotope.ChainSign).
                    addToChain("Co-60").
                    addToChain("Ba-133").
                    addToChain("Cs-137").
                    addToChain("Ir-192").
                    addToChain("Eu-152").
                    addToChain("Na-22").
                    addToChain("Am-241"),
            //Special
            new Isotope("U-233", Isotope.ChainSign).
                    addToChain("U-235").
                    addToChain("Np-237").
                    addToChain("Pu-239").
                    addToChain("Pu-240").
                    addToChain("Pu-241"),
            //Normal
            new Isotope("K-40", Isotope.ChainSign).
                    addToChain("La-138").
                    addToChain("Ra-226").
                    addToChain("Th-232").
                    addToChain("Ra-228").
                    addToChain("Ac-228").
                    addToChain("Th-228").
                    addToChain("Ra-224").
                    addToChain("Rn-220").
                    addToChain("Po-216").
                    addToChain("Pb-212").
                    addToChain("Bi-212").
                    addToChain("Po-212").
                    addToChain("Tl-208").
                    addToChain("U-238").
                    addToChain("Th-234").
                    addToChain("Pa-234m").
                    addToChain("Pa-234").
                    addToChain("U-234").
                    addToChain("Th-230").
                    addToChain("Ra-226").
                    addToChain("Rn-222").
                    addToChain("Po-218").
                    addToChain("At-218").
                    addToChain("Rn-218").
                    addToChain("Pb-214").
                    addToChain("Bi-214").
                    addToChain("Po-214").
                    addToChain("Tl-210").
                    addToChain("Pb-210").
                    addToChain("Bi-210").
                    addToChain("Po-210").
                    addToChain("Tl-206"),
    };
    public static ArrayList<LinkedList<Integer>> Chains = new ArrayList<>();
    public static boolean[] checkedChains = new boolean[decayChains.length];

    public static LinkedList<Isotope> energyList = new LinkedList<>();
    public static LinkedList<Isotope> isotopeList = new LinkedList<>();
    public static LinkedList<LinkedList<Integer>> energyArray = new LinkedList<>();
    public static boolean[] checkedIsotope = null;

    public static LinkedList<Isotope> foundList = new LinkedList<>();
    public static boolean showFoundIsotopes = false;
    public static boolean autoUpdateIsotopes = false;
    public static int isotopeLibrary = 0;

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences sharedPreferences = newBase.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = sharedPreferences.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
        String lang = Locale.getDefault().getLanguage();
        if (r > 0) {
            lang = Constants.LOCALES_ID[r];
        }
        super.attachBaseContext(MyContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_isotopes);
        ActionBar actionBar = getActionBar();
        try {
            if (actionBar != null) {
                getActionBar().setDisplayShowHomeEnabled(true);
                getActionBar().setDisplayHomeAsUpEnabled(true);
            }
        } catch (Exception e) {
            //nothing
        }
        //one listener for all elements
        final OnClickListener listener = v -> {
            int num = v.getId();
            if ((num >= Constants.GROUPS.GROUP_ID_ALIGN) && (num < Constants.GROUPS.ENERGY_ID_ALIGN)) {
                checkedChains[num - Constants.GROUPS.GROUP_ID_ALIGN] = ((CheckBox) v).isChecked();
                for (int pos : Chains.get(num - Constants.GROUPS.GROUP_ID_ALIGN)) {
                    checkedIsotopeLine[pos] = ((CheckBox) v).isChecked();
                    ((CheckBox) findViewById(pos + Constants.GROUPS.BUTTON_ID_ALIGN)).setChecked(((CheckBox) v).isChecked());
                }
                for (String c : decayChains[num - Constants.GROUPS.GROUP_ID_ALIGN].Chain) {
                    for (int pos = 0; pos < energyList.size(); pos++) {
                        if (energyList.get(pos).equals(new Isotope(c, Isotope.Stable))) {
                            checkedIsotope[pos] = ((CheckBox) v).isChecked();
                            ((CheckBox) findViewById(pos + Constants.GROUPS.ENERGY_ID_ALIGN)).setChecked(((CheckBox) v).isChecked());
                            break;
                        }
                    }
                }
            } else if ((num >= Constants.GROUPS.ENERGY_ID_ALIGN) && (num < Constants.GROUPS.ENERGY_ID_HALF_LIFE_ALIGN)) {
                checkedIsotope[num - Constants.GROUPS.ENERGY_ID_ALIGN] = ((CheckBox) v).isChecked();
                for (int pos : energyArray.get(num - Constants.GROUPS.ENERGY_ID_ALIGN)) {
                    checkedIsotopeLine[pos] = ((CheckBox) v).isChecked();
                    ((CheckBox) findViewById(pos + Constants.GROUPS.BUTTON_ID_ALIGN)).setChecked(((CheckBox) v).isChecked());
                }
            } else if ((num >= Constants.GROUPS.BUTTON_ID_ALIGN) && (num < (Constants.GROUPS.BUTTON_ID_ALIGN + checkedIsotopeLine.length))) {
                checkedIsotopeLine[num - Constants.GROUPS.BUTTON_ID_ALIGN] = ((CheckBox) v).isChecked();
            }
        };
        //Fill decay chains with indexes
        for (int i = 0; i < decayChains.length; i++) {
            Chains.add(new LinkedList<>());
            for (String j : decayChains[i].Chain) {
                for (int k = 0; k < isotopeLineArray.size(); k++) {
                    if (j.equals(isotopeLineArray.get(k).getName()))
                        Chains.get(i).addLast(k);
                }
            }
        }
        //Fill all energy lists
        if (isotopeList.isEmpty()) {
            int idx;
            for (Isotope data : isotopeLineArray) {
                idx = -1;
                for (int i = 0; i < isotopeList.size(); i++) {
                    if (isotopeList.get(i).equals(data)) {
                        idx = i;
                        break;
                    }
                }
                if (idx == -1) {
                    int pos = 0;
                    for (Isotope name : isotopeList) {
                        if (data.compareTo(name) > 0)
                            pos++;
                    }
                    isotopeList.add(pos, data);
                    energyList.add(pos, new Isotope(data));
                } else {
                    energyList.get(idx).addEnergy(data);
                }
            }
            //energyArray.clear();
            checkedIsotope = new boolean[energyList.size()];
            for (int i = 0; i < energyList.size(); i++) {
                energyArray.add(new LinkedList<>());
                for (int j = 0; j < isotopeLineArray.size(); j++) {
                    if (isotopeLineArray.get(j).getName().equals(isotopeList.get(i).getName())) {
                        energyArray.get(i).addLast(j);
                    }
                }
            }
        }

        //Read data from saved state
        if (savedInstanceState != null) {
            for (int i = 0; i < checkedChains.length; i++)
                checkedChains[i] = savedInstanceState.getBoolean(ISOTOPE_CHAIN_STATE + i, false);

            for (int i = 0; i < checkedIsotope.length; i++)
                checkedIsotope[i] = savedInstanceState.getBoolean(ISOTOPE_STATE + i, false);

            for (int i = 0; i < checkedIsotopeLine.length; i++)
                checkedIsotopeLine[i] = savedInstanceState.getBoolean(ISOTOPE_LINE_STATE + i, false);
        }

        //Add decay chains to isotope list
        LinearLayout layout = findViewById(R.id.isotope_chain_layout);
        LinearLayout itemLayout;               //Test
        CheckBox itemName;
        TextView itemIntense;
        TextView itemEnergy;                   //Test
        for (int i = 0; i < decayChains.length; i++) {
            itemName = new CheckBox(this);
            itemName.setText(decayChains[i].toString(getResources()));
            itemName.setTextSize(22);
            itemName.setMinHeight(28);
            itemName.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            itemName.setChecked(checkedChains[i]);
            itemName.setId(Constants.GROUPS.GROUP_ID_ALIGN + i);
            itemName.setTextColor(Isotope.getColorForChain());
            itemName.setEnabled(true);
            itemName.setClickable(true);
            itemName.setOnClickListener(listener);
            layout.addView(itemName);
        }
        layout = findViewById(R.id.isotope_layout);
        Paint textInfo = new Paint();
        textInfo.setTextSize(22);
        Rect isotopeRect = new Rect();
        int isotopeWidth = (int) textInfo.measureText("XX-888m ");
        Rect energyRect = new Rect();
        int energyWidth = (int) textInfo.measureText("9999_99 keV ");
        Rect intenseRect = new Rect();
        int intenseWidth = (int) textInfo.measureText("999_999");
        textInfo.getTextBounds("XX-888m ", 0, 8, isotopeRect);
        textInfo.getTextBounds("9999_99 keV ", 0, 12, energyRect);
        textInfo.getTextBounds("999_999", 0, 7, intenseRect);
        LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        layoutParams.weight = 1.0f;
        //add combined isotope lines
        boolean isSelected;
        Typeface tf;
        int size;
        for (int i = 0; i < energyArray.size(); i++) {
            isSelected = isotopeLibrary > 0 && AtomSpectraIsotopes.IAEAList[AtomSpectraIsotopes.isotopeLibrary - 1].isInChain(AtomSpectraIsotopes.energyList.get(i).getName());
            tf = isSelected ?
                    Typeface.defaultFromStyle(Typeface.BOLD) :
                    Typeface.defaultFromStyle(Typeface.NORMAL);
            size = 14 + (isSelected || isotopeLibrary == 0 ? 8 : 0);
            itemLayout = new LinearLayout(this);
//            itemLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
            itemLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemName = new CheckBox(this);
            itemName.setText(energyList.get(i).getName());
            itemName.setTextSize(size);
            itemName.setTypeface(tf);
            itemName.setMinHeight(30);
            itemName.setLayoutParams(layoutParams);
            itemName.setMinWidth(isotopeWidth + itemName.getCompoundPaddingLeft() + itemName.getCompoundPaddingRight());
            itemName.setChecked(checkedIsotope[i]);
            itemName.setId(Constants.GROUPS.ENERGY_ID_ALIGN + i);
            itemName.setTextColor(Isotope.getColorForIsotope());
            itemName.setEnabled(true);
            itemName.setClickable(true);
            itemName.setOnClickListener(listener);
            itemEnergy = new TextView(this);
            itemEnergy.setTextColor(Isotope.getColorForIsotope());
            itemEnergy.setTextSize(size);
            itemEnergy.setMinHeight(30);
            itemEnergy.setTypeface(tf);
            itemEnergy.setGravity(Gravity.START);
            itemEnergy.setId(Constants.GROUPS.ENERGY_ID_HALF_LIFE_ALIGN + i);
//            itemEnergy.setMinWidth((int) (energyRect.width() * dm.scaledDensity));
            itemEnergy.setMinWidth(energyWidth);
            itemEnergy.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_END);
            itemEnergy.setText(energyList.get(i).getHalfLifeName(getResources()));
            itemEnergy.setLayoutParams(layoutParams);
            itemLayout.addView(itemName);
            itemLayout.addView(itemEnergy);
            layout.addView(itemLayout);
        }
        //add individual isotope lines
        layout = findViewById(R.id.isotope_line_layout);
        //DisplayMetrics dm = getBaseContext().getResources().getDisplayMetrics();
        for (int i = 0; i < isotopeLineArray.size(); i++) {
            isSelected = isotopeLibrary > 0 && AtomSpectraIsotopes.IAEAList[AtomSpectraIsotopes.isotopeLibrary - 1].isInChain(AtomSpectraIsotopes.isotopeLineArray.get(i).getName());
            tf = isSelected ?
                    Typeface.defaultFromStyle(Typeface.BOLD) :
                    Typeface.defaultFromStyle(Typeface.NORMAL);
            size = 14 + (isSelected || isotopeLibrary == 0 ? 8 : 0);
            itemLayout = new LinearLayout(this);
//            itemLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
            itemLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemName = new CheckBox(this);
            itemName.setText(isotopeLineArray.get(i).getName());
            itemName.setTextSize(size);
            itemName.setMinHeight(30);
            itemName.setTypeface(tf);
            itemName.setLayoutParams(layoutParams);
//            itemName.setMinWidth((int) (isotopeRect.width() * dm.scaledDensity) + itemName.getCompoundPaddingLeft() + itemName.getCompoundPaddingRight());
            itemName.setMinWidth(isotopeWidth + itemName.getCompoundPaddingLeft() + itemName.getCompoundPaddingRight());
//            itemName.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT, 1.0f));
            itemName.setChecked(checkedIsotopeLine[i]);
            itemName.setId(Constants.GROUPS.BUTTON_ID_ALIGN + i);
            //show main isotopes with different colour
            itemName.setTextColor(isotopeLineArray.get(i).getColor());
            itemName.setEnabled(true);
            itemName.setClickable(true);
            itemName.setOnClickListener(listener);
            itemEnergy = new TextView(this);
            itemEnergy.setTextColor(isotopeLineArray.get(i).getColor());
            itemEnergy.setTextSize(size);
            itemEnergy.setMinHeight(30);
            itemEnergy.setTypeface(tf);
            itemEnergy.setGravity(Gravity.END);
            itemEnergy.setId(Constants.GROUPS.BUTTON_ENERGY_ID_ALIGN + i);
//            itemEnergy.setMinWidth((int) (energyRect.width() * dm.scaledDensity));
            itemEnergy.setMinWidth(energyWidth);
            itemEnergy.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_END);
            itemEnergy.setText(getString(R.string.isotope_show_energy, isotopeLineArray.get(i).getEnergy(0)));
            itemEnergy.setLayoutParams(layoutParams);
//            itemEnergy.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT, 1.0f));

            itemIntense = new TextView(this);
            itemIntense.setTextColor(isotopeLineArray.get(i).getColor());
            itemIntense.setTextSize(size);
            itemIntense.setMinHeight(30);
            itemIntense.setTypeface(tf);
            itemIntense.setGravity(Gravity.END);
            itemIntense.setId(Constants.GROUPS.BUTTON_INTENSE_ID_ALIGN + i);
//            itemIntense.setMinWidth((int) (intenseRect.width() * dm.scaledDensity));
            itemIntense.setMinWidth(intenseWidth);
            itemIntense.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_END);
            itemIntense.setText(String.format(Locale.getDefault(), "%.2f", isotopeLineArray.get(i).getIntensity(0)));
            itemIntense.setLayoutParams(layoutParams);
//            itemIntense.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT, 1.0f));

            itemLayout.addView(itemName);
            itemLayout.addView(itemEnergy);
            itemLayout.addView(itemIntense);
            layout.addView(itemLayout);
        }
        Spinner spinner = findViewById(R.id.selectLibSpinner);
        spinner.setOnItemSelectedListener(this);
        spinner.setSelection(isotopeLibrary);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_ISOTOPES);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mDataUpdateReceiver, intentFilter);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
//            onBackPressed();
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        for (int i = 0; i < checkedChains.length; i++)
            outState.putBoolean(ISOTOPE_CHAIN_STATE + i, checkedChains[i]);

        for (int i = 0; i < checkedIsotope.length; i++)
            outState.putBoolean(ISOTOPE_STATE + i, checkedIsotope[i]);

        for (int i = 0; i < checkedIsotopeLine.length; i++)
            outState.putBoolean(ISOTOPE_LINE_STATE + i, checkedIsotopeLine[i]);
    }

    public static String findIsotopeByEnergy(double energy, boolean use_importance, int low_importance) {
        String result = String.format(Locale.US, "Not identified (%1$.2f)", energy);
        double delta = energy;
//        int importance = low_importance;
        for (Isotope isotope : isotopeLineArray) {
//            if (StrictMath.abs(isotope.getEnergy() - energy) > (energy*0.1))
//                continue;
            if ((isotope.getEnergy(0) - energy) < -(energy * 0.05))
                continue;
            if ((isotope.getEnergy(0) - energy) > (energy * 0.05))
                break;
//            if (isotope.getImportance() < importance && use_importance)
//                continue;
//            if (isotope.getImportance() > importance && use_importance) {
//                importance = isotope.getImportance();
//                delta = StrictMath.abs(isotope.getEnergy() - energy);
//                result = isotope.toString();
//                continue;
//            }
            if (StrictMath.abs(isotope.getEnergy(0) - energy) < delta) {
                delta = StrictMath.abs(isotope.getEnergy(0) - energy);
                result = isotope.toString();
            }
        }
        return result;
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Constants.ACTION.ACTION_CLOSE_ISOTOPES.equals(action)) {
                finish();
            }

        }

    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDataUpdateReceiver != null) unregisterReceiver(mDataUpdateReceiver);
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent.getId() == R.id.selectLibSpinner) {
            isotopeLibrary = position;
            CheckBox itemName;
            TextView itemIntense;
            TextView itemEnergy;
            boolean isSelected;
            Typeface tf;
            int size;
            for (int i = 0; i < energyArray.size(); i++) {
                isSelected = isotopeLibrary > 0 && AtomSpectraIsotopes.IAEAList[AtomSpectraIsotopes.isotopeLibrary - 1].isInChain(AtomSpectraIsotopes.energyList.get(i).getName());
                tf = isSelected ?
                        Typeface.defaultFromStyle(Typeface.BOLD) :
                        Typeface.defaultFromStyle(Typeface.NORMAL);
                size = 14 + (isSelected || isotopeLibrary == 0 ? 8 : 0);
                itemName = findViewById(Constants.GROUPS.ENERGY_ID_ALIGN + i);
                itemName.setTextSize(size);
                itemName.setTypeface(tf);
                itemEnergy = findViewById(Constants.GROUPS.ENERGY_ID_HALF_LIFE_ALIGN + i);
                itemEnergy.setTypeface(tf);
                itemEnergy.setTextSize(size);
            }
            for (int i = 0; i < isotopeLineArray.size(); i++) {
                isSelected = isotopeLibrary > 0 && AtomSpectraIsotopes.IAEAList[AtomSpectraIsotopes.isotopeLibrary - 1].isInChain(AtomSpectraIsotopes.isotopeLineArray.get(i).getName());
                tf = isSelected ?
                        Typeface.defaultFromStyle(Typeface.BOLD) :
                        Typeface.defaultFromStyle(Typeface.NORMAL);
                size = 14 + (isSelected || isotopeLibrary == 0 ? 8 : 0);
                itemName = findViewById(Constants.GROUPS.BUTTON_ID_ALIGN + i);
                itemName.setTypeface(tf);
                itemName.setTextSize(size);
                itemEnergy = findViewById(Constants.GROUPS.BUTTON_ENERGY_ID_ALIGN + i);
                itemEnergy.setTypeface(tf);
                itemEnergy.setTextSize(size);
                itemIntense = findViewById(Constants.GROUPS.BUTTON_INTENSE_ID_ALIGN + i);
                itemIntense.setTypeface(tf);
                itemIntense.setTextSize(size);
            }
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        //nothing to do
    }
}

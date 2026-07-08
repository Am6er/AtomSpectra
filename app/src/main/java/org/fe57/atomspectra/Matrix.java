package org.fe57.atomspectra;

public class Matrix {
    public final double[][] array;

    public Matrix(int rows, int cols) {
        if (rows < 1 || cols < 1) {
            throw new IllegalArgumentException(
                    "Matrix dimensions must be positive: rows=" + rows + ", cols=" + cols);
        }
        array = new double[rows][cols];
    }

    public Matrix(Matrix matrix) {
        array = new double[matrix.array.length][matrix.array[0].length];
        for (int i = 0; i < array.length; i++)
            System.arraycopy(matrix.array[i], 0, array[i], 0, array[0].length);
    }

    //C=this^T
    public Matrix Transpose() {
        Matrix out = new Matrix(array[0].length, array.length);
        for (int i = 0; i < array.length; i++)
            for (int j = 0; j < array[0].length; j++)
                out.array[j][i] = array[i][j];
        return out;
    }

    //C=this^(-1)
    public Matrix Inverse() {
        if (array.length != array[0].length)
            throw new IllegalArgumentException("Matrix must be square to invert");
        //I'll use 2 matrices: (A|E) not combined together
        int array_size = array.length;
        double[][] A = new double[array_size][array_size];
        double[][] E = new double[array_size][array_size];
        for (int i = 0; i < array_size; i++) {
            System.arraycopy(array[i], 0, A[i], 0, array_size);
            E[i][i] = 1.0;
        }
        //calculate the inverse matrix
        //forward steps
        double reduce;
        final double EPSILON = 1e-10;
        for (int i = 0; i < array_size; i++) {
            if (Math.abs(A[i][i]) < EPSILON) {
                int non_zero = -1;
                for (int j = i + 1; j < array_size; j++) {
                    if (Math.abs(A[j][i]) >= EPSILON) {
                        non_zero = j;
                        break;
                    }
                }
                if (non_zero == -1)
                    throw new ArithmeticException("Matrix is singular");
                double swap;
                //swap two lines
                for (int j = 0; j < array_size; j++) {
                    swap = A[non_zero][j];
                    A[non_zero][j] = A[i][j];
                    A[i][j] = swap;
                    swap = E[non_zero][j];
                    E[non_zero][j] = E[i][j];
                    E[i][j] = swap;
                }
                //now we have line i with non-zero element at [i][i];
            }
            //make A[i][i] equal to 1.0
            reduce = A[i][i];
            for (int j = 0; j < array_size; j++) {
                A[i][j] /= reduce;
                E[i][j] /= reduce;
            }
            //subtract line i from other subsequent lines
            for (int j = i + 1; j < array_size; j++) {
                reduce = A[j][i];
                for (int k = 0; k < array_size; k++) {
                    A[j][k] -= reduce * A[i][k];
                    E[j][k] -= reduce * E[i][k];
                }
            }
        }
        //reverse steps
        for (int i = array_size - 1; i >= 1; i--) {
            for (int j = i - 1; j >=0; j--) {
                reduce = A[j][i];
                for (int k = 0; k < array_size; k++) {
                    A[j][k] -= reduce * A[i][k];
                    E[j][k] -= reduce * E[i][k];
                }
            }
        }
        Matrix out = new Matrix(array_size, array_size);
        for (int i = 0; i < array_size; i++)
            System.arraycopy(E[i], 0, out.array[i], 0, array_size);
        return out;
    }

    //C=this*matrix
    public Matrix Times(Matrix matrix) {
        if (array[0].length != matrix.array.length)
            throw new IllegalArgumentException("Incompatible matrix dimensions for multiplication");

        Matrix out = new Matrix(array.length, matrix.array[0].length);
        for (int i = 0; i < array.length; i++) {
            for (int j = 0; j < matrix.array[0].length; j++)
                for (int k = 0; k < array[0].length; k++)
                    out.array[i][j] += array[i][k] * matrix.array[k][j];
        }
        return out;
    }

}

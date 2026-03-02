#  Map-aided Adaptive Particle Filter with a Reduced State Space

This is a map-based adaptive particle filtering pedestrian dead reckoning (PF-PDR) using IMU sensors. The repository contains the official implementation of,

A Novel Smartphone PDR Framework Based on Map-Aided Adaptive Particle Filter with a Reduced State Space  
**Mengchi Ai, Ilyar Asl Sabbaghian Hokmabadi, Xuan Zhao**  
*ISPRS International Journal of Geo-Information*, 2025, 14(12), 476  
[[Journal Link]](https://www.mdpi.com/2220-9964/14/12/476)

# How to run the code

Currently, all the required functions and the main code are included in one MATLAB file. The main MATLAB function includes helper inner functions to aid with a modular design but also reduce the number of additional files. All the required MATLAB functions are inside one larger function for ease of use. 

In order to run the code, the paths in `PDR_PF_2D_param_initialization()` should be modified. `PDR_PF_2D_param_initialization()` includes hyperparameters to be tuned. These are included in the following sections. 

# Folder Structure
 The user is expected to have the following data folder structure. 
- /DXF_2_CSV
  exported_lines_olderanddooropen.csv <- sample map coordinates x1, y1, x2, y2
- /MATLAB/APF_PDR
  - /sample_data/Samsung_A16_Ilyar_2025_07_22_01_41_49/
    - /calibration/ <- Intrinsic calibration matrices
      - gyros_calib.mat
      - accel_calib.mat
    - /navigation/  <- sample PDR data collected on Samsung A16 using Sensor Logger app

The repository includes a sample map. The map is saved in a CSV file inside DXF_2_CSV. The rows in this text file correspond to a line segment in the map (x1,y1,x2,y2). This map is not in the correct scale; however, the provided code will apply the correct scale to it. More information about the scaling of the map is provided in this README.md file. 

A sample data collected on Samsung A16 using Sensor Logger is also provided inside MATLAB/APF_PDR/sample_data/Samsung_A16_Ilyar_2025_07_22_01_41_49/navigation/. The calibration matrices are provided in MATLAB/APF_PDR/sample_data/Samsung_A16_Ilyar_2025_07_22_01_41_49/calibration/.

Calibration for the accelerometer and gyroscope is NOT REQUIRED. However, it is recommended to perform a calibration for accelerometer data. The calibration files should be saved in native MATLAB format (`.mat`) for each triaxial gyroscope and accelerometer (the names of these files are expected to be `accel_calib.mat` and `gyros_calib.mat`). See the calibration section for more information below.

The user should also provide IMU filenames as `init_02_IMU_filename`. The first argument helps with parsing the IMU data. Here, the user can define their own device (granted that a custom reader is coded inside `PDR_PF_2D_load_IMU` function). The algorithm is tested using the Sensor Logger App, which is available to download on Android devices. Sensor Logger produces accelerometer and gyroscope files in separate CSV files. When reading the files, ensure the data is converted to a matrix of the following format.

`timestamp, accelerometer x (m/s^2), accelerometer y (m/s^2), accelerometer z (m/s^2), gyroscope x (deg/second), gyroscope y (deg/second), gyroscope z (deg/second)`.

The user can write their own file to convert the inputs to the format mentioned above for compatibility with the rest of the algorithm.

# Hyperparameters
Hyperparameters are all included in `PDR_PF_2D_param_initialization()` inner function. The user can set the initial and running particle filter parameters. In particular, two variables, `init_08_sampling_size` and `init_09_cross_entropy` are important. In the example below, the initial particle number is set to 2000, and the initial particle heading is set to a range from -91 to 89. Gyro bias noise standard deviation is set to 0.03, and step length noise standard deviation is set to 0.4. Particle filter for reduction is set to 1000.

```ruby
   init_08_sampling_size   = {2000,{-91:1:-89},{0.03,0.4},1000}; 
```

In the following example, the heading threshold for cross entropy is set to 1.8 degrees, and the position threshold is set to 3.0. Larger thresholds will lead to earlier triggering of the particle reduction, while smaller thresholds will lead to a later trigger reduction.

```ruby
  init_09_cross_entropy = {1.8,3.0}; 
```

# Calibration (optional)
The inclusion of IMU calibration is not required. A calibration can be removed by setting NO_CALIBRATION at the very beginning of the file. We highly recommend using the calibration procedure. The output of the calibration should be a 4 by 4 matrix for each sensor, as shown below:

$$
\mathbf{K}_{\text{gyro}} =
\begin{bmatrix}
s_{g_x} & m_{g_{xy}} & m_{g_{xz}} & b_{g_x} \\
m_{g_{yx}} & s_{g_y} & m_{g_{yz}} & b_{g_y} \\
m_{g_{zx}} & m_{g_{zy}} & s_{g_z} & b_{g_z} \\
0 & 0 & 0 & 1
\end{bmatrix}
$$

$$
\mathbf{K}_{\text{acc}} =
\begin{bmatrix}
s_{a_x} & m_{a_{xy}} & m_{a_{xz}} & b_{a_x} \\
m_{a_{yx}} & s_{a_y} & m_{a_{yz}} & b_{a_y} \\
m_{a_{zx}} & m_{a_{zy}} & s_{a_z} & b_{a_z} \\
0 & 0 & 0 & 1
\end{bmatrix}
$$

Although a particle filter is a robust estimator, it will suffer from uncalibrated gyroscopes to some extent (especially with a significant gyroscope bias). The algorithm DOES NOT remove the initial bias values from a gyroscope, and a particle filter can only accommodate up to 3 degrees of  bias in the tested scenario. 

# Inclusion of static phases 
It is recommended to have 1 1-second pause in the beginning (user remains stationary). The one-second pause is utilized to approximate the axis that corresponds to the vertical direction. The user needs to mention the last epoch (before pause). Exact end time of the static pause is NOT REQUIRED. If the user provided 1 1-second pause and the data rate is 100, then the first number in `init_02_IMU_filename` should be set to 100 (the second number is the index of the final epoch).

# Step length estimation
Step length estimation is important in any PDR. In this code, the step length is estimated by detecting the acceleration peaks and the valleys in the vertical direction (parallel to the floor). In the `PDR_PF_2D_step_detect` function, the accelerometer readings are projected onto the vertical direction. We assume that the floor is flat, and if the smartphone is placed on the floor, there will be an alignment of the floor plane and one of the axes of the accelerometer (Excat alignment is NOT REQUIRED, as the estimated length of the step will be corrected through particle filter). With these assumptions, accelerometer measurements are projected onto the vertical direction. and the minimum and the maximum values in this direction are detected. Detecting maximum and minimum requires identifying a window `window_size`. This variable is local to `PDR_PF_2D_step_detect` and is set AUTOMATICALLY. The value of this window size can be changed if you receive a warning that "Number of peaks and valleys are different" in MATLAB prompt. Typically, if the peaks and valleys are different up to 10-14 steps, the algorithm runs without issues, but if this difference increases, it can cause failures. 

The detected epochs of the maximum and the minimum accelerometer values and accelerometer values themselves are passed to `PDR_PF_2D_estimate_length`, where we utilized Weinberg's step length estimation formula. This formula requires setting a constant value (k = 0.4). This value is only approximate and user-dependent. Exact values for this parameter are NOT REQUIRED, as the particle filter through map-updates can handle errors.

Butterworh frequency filtering is utilized to remove high-frequency error in the data. Currently, a cut-off frequency(`cutoff`) of 8 Hz is utilized. The cut-off frequency can be decreased or increased based on the type of IMU used. 

# Generating initial particles
Generating an initial particle happens in a few steps. First, the algorithm reads a CSV file containing the map `PDR_PF_2D_read_CAD`. This is a vectorized dataset, where each line corresponds to x1,y1,x2,y2, referring to the first and last points on a line segment. If the CAD model is in the correct map scale, then `init_05_map_information` scale should be set to 1. In our case, a scale factor of `4.3197` was used to transfer the CAD scale to the  correct metric scale. 

Following this step, `PDR_PF_2D_gen_occupy` uses imported map-lines and places them in a discretized matrix (found in the DXF_2_CSV folder in this page). The resolution of this map is defined based on `resolution` should be fine (small enough) to preserve all the structures in the map. `grid_D = imdilate(grid, ones(4,4))` line is used to connect the nearby points in the discretized points and avoid empty gaps in the map lines.

The discrete map is utilized to spawn a possible set of user positions in `PDR_PF_2D_init_occupy`. If the user is interested in selecting particles in a specific region of the map, they can do so by setting `lim_x_lower`, `lim_x_upper`, `lim_y_lower`, and `lim_y_upper` values. Otherwise, these values can be set to `inf` and `-inf`. Based on the desired number of particles `par_num_pos`, the function will select particles at regular spaces matching closest to this number. Finally, the grid coordinates are transformed to original map-scale values matching the real dimensions of the CAD model in this function (thus `m_par` is the world scale).

It is important to note that sampling the user's heading will multiply `par_num_pos`. So the heading sampling range is set in `init_08_sampling_size`(second cell). `par_num_pos` will increase by a factor equal to the total number of headings in this range. If the user requires the same number of particles as `init_08_sampling_size`, then an approximate heading should be provided. This can be done by setting the initial heading spread to only include the heading. Exact knowledge of heading is NOT REQUIRED, and approximate values up to 5 degrees should work. If the user is not sure about the heading, then the heading range should be expanded.

# Adaptive particle filtering
Particle filtering follows[[Journal Link]](https://www.mdpi.com/2220-9964/14/12/476) which this work is based on. The proposed particle filtering is map-aided, where particles are propagated forward with the help of IMU measurements.

While accelerometer measurements are used to estimate the step length, gyroscope measurements are utilized to estimate the yaw angle. The following are the distinctions between the proposed particle filter and other particle filters:
  1. The proposed method relies on leveling gyroscope measurements using the gravity vector from the accelerometer. Since each accelerometer reading can estimate the gravity vector independently, such leveling can be achieved independently of the particle filtering process, and thus no additional requirements for state augmentation are required. Keeping the number of the state vector low will reduce the computational cost of the proposed particle filter. 
  2. The proposed particle filter takes into account the bias in the gyroscope as part of the state variable. Gyroscope bias is a time-varying state that cannot be calibrated prior to the PDR mission for most MEMS-based IMUs. 
  3. The proposed particle filter does not sample x and y directions and instead samples the step length. Due to the special assumptions about the locomotion of a human, the x and y are related to each other through step length and step direction. Thus, the parameters are not sampled independently.

Particle prediction is achieved in `particle_filter_predict`.

The developed algorithm utilizes the map to update the particles. Two types of map updates are included. The main and important map update checks to see if a particle moves throughout the walls defined the 2D plan of the building. Particle updates can be found in `particle_filter_update1` and `particle_filter_update2`. It is important to note that such a particle update will only occur if the user takes a step.

After every particle filter update, the developed method utilizes cross cross-entropy distance of the particle filter spread and a Gaussian distribution. If this distance is smaller than a certain value, it means particles are converging, and particle numbers can be reduced. Cross-entropy test is performed in `PDR_PF_2D_CE_test`. The parameter to tune in this function includes mainly the covariance of the target distribution, which is defined with the local (to function) parameter `gaussian_cov`. The minimum distance acceptable from this distribution is set using `CE_posisti_threshold` and `CE_heading_threshold`. 

TODO: In the current implementation, particle reduction is triggered once. More robust methods can increase the number of particles if cross cross-entropy distance falls outside of a certain range. 

# Example
![Alt text](/Matlab/APF_PDR/20251106_205017_Mode_reduced_ParNumInit6000_GyroBias0.03_StepLengthNoise0.4ParReduced1000.gif)

# License
APF_PDR is released under BSD-3 License.

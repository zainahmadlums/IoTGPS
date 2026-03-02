%#ok<*LOAD>
%#ok<*AGROW>
%#ok<*INUSD>
%#ok<*GVMIS>

close all;clear;set(0, 'DefaultFigureWindowStyle', 'docked');
set(0, 'DefaultFigureVisible', 'on')


% Quick configuration of the code
CALIBRATE_IMU = 0;

%---------------------
% initialize parameters
%---------------------
param_init = PDR_PF_2D_param_initialization();

%---------------------
% read map lines
%---------------------
[map_lines]= PDR_PF_2D_read_CAD(param_init);
valid_idx1 = abs(map_lines(:,1) + 0.49)<0.1 & abs(map_lines(:,2) + 6.52)<0.1 & abs(map_lines(:,3) - 5.52)<0.1 & abs(map_lines(:,4) + 6.54)<0.1;
valid_idx2 = abs(map_lines(:,1) + 4.10)<0.1 & abs(map_lines(:,2) + 8.17)<0.1 & abs(map_lines(:,3) + 4.10)<0.1 & abs(map_lines(:,4) + 7.31)<0.1;
valid_idx3 = abs(map_lines(:,3) + 5.83)<0.1 & abs(map_lines(:,4) + 7.60)<0.1 & abs(map_lines(:,1) + 5.83)<0.1 & abs(map_lines(:,2) + 6.67)<0.1;
valid_idx = valid_idx1 | valid_idx2 | valid_idx3;
map_lines(valid_idx,:) = [];

%---------------------
% convert map to occupancy grids
%---------------------
[map_occupy,map_lines,map_lines_h,x_min,y_min] = PDR_PF_2D_gen_occupy(param_init,map_lines);

%---------------------
% initialize particles
%---------------------
[m_par,bound_test_map] = PDR_PF_2D_init_occupy(param_init,map_occupy,x_min,y_min);
bound_test_flat = bound_test_map(:);

%---------------------
% read IMU data
%---------------------
IMU_all = PDR_PF_2D_load_IMU(param_init);

%---------------------
% calibrate IMU data
%---------------------
if CALIBRATE_IMU == 1
    IMU_all_calib = PDR_PF_2D_calib_IMU(param_init,IMU_all);
else
    IMU_all_calib = IMU_all;
end

%---------------------
% filter IMU data
%---------------------
IMU_all_calib = PDR_PF_2D_filt_IMU(param_init,IMU_all_calib);

%---------------------
% detect steps
%---------------------
[peak_indices,peak_values,low_indices,low_values,accel_v_all,v_dir] = PDR_PF_2D_step_detect(param_init,IMU_all_calib);
step_flag = zeros(1,size(IMU_all_calib,1));
step_flag(peak_indices)= 1;

%---------------------
% detect step lengths
%---------------------
[step_leng,step_leng_flagged] = PDR_PF_2D_estimate_length(param_init,IMU_all_calib,accel_v_all,peak_indices,low_indices);

%---------------------
% initialize variables
%---------------------
c_state = m_par;    % set the current state as the generate initial particles

c_weights = (1/length(c_state)) * ones(1,length(c_state)); % set the current weights
imu_time_sec = IMU_all_calib(:,1);
time_diff_sec = diff(imu_time_sec); % calculate the delta_t
step_count = 0;
global previous_step_location
previous_step_location = c_state;
global gif_filename


%---------------------
% initialize other variables
%---------------------
global hist_gyro_bias
hist_gyro_bias             = [];
hist_gyro_bias_mean        = [];
hist_heading_mean          = []; % heading for steps
hist_CE                    = []; 
hist_pos_mean              = []; % position for steps
hist_step_length           = []; 
hist_pos_complete_mean     = []; % position for every epoch
hist_heading_complete_mean = []; % heading for every epoch

%---------------------
% Main filtering loop
%---------------------
static_plot_trigger = 0;
particle_count      = 0;
kalman_count        = 0;
t_iteration_all     = 0;
already_reduced     = 0;
t_ce_all            = 0;

global  Kl_first_trigger_step
Kl_first_trigger_step = inf;

for i = 1 : size(IMU_all_calib,1) -1

    t_ellipse_fitting = 0;
    t_pf_update       = 0;
    t_pf_divergence   = 0;
    t_ce = 0;

    %  1. fetch IMU_all_calib
    c_IMU  = IMU_all_calib(i,:);
    delta_t = time_diff_sec(i);

    %  2. correct gyroscope heading in the local level frame
    c_ang_rate= PDR_PF_2D_grav_based_att_correction(param_init,c_IMU,v_dir);

    %  3. check to see if there an step taken
    step_flag_this = step_flag(i);
    step_leng = step_leng_flagged(i);


    % 4. update the position +add noise(sampling)
    tic;
    [n_state,delta_v_h,delta_v_lines] = particle_filter_predict(param_init,c_state,c_ang_rate,delta_t,step_flag_this,step_leng);
    t_pf_predict = toc; %<------- time measure here
    
    % 5.
    if step_flag_this == 1

        step_count = step_count+1;

        % 5.1 weight particles
        tic;
        n_weights = particle_filter_update1(param_init,map_lines,map_lines_h,delta_v_lines,delta_v_h,c_weights);
        n_weights = particle_filter_update2(param_init,n_state,n_weights,bound_test_map,bound_test_flat,x_min,y_min);
        t_pf_update = toc;%<------- time measure here

        % 5.2 resample
        [r_state,r_weight] = particle_filter_resample(n_state,n_weights);


        % 5.3 check Cross entropy
        tic;
        [pf_reduction_flag,CE] = PDR_PF_2D_CE_test(param_init,r_state,r_weight);
        t_ce=toc;

        % 5.4 state renaming
        c_state    = r_state;
        c_weights  = r_weight;

        % 5.5 save param hist. for the plots/analysis
        hist_gyro_bias_mean = [hist_gyro_bias_mean,mean(c_state(:,4))];
        hist_heading_mean   = [hist_heading_mean,mean(c_state(:,3)).*(180/pi)];
        hist_pos_mean       = [hist_pos_mean;mean(c_state(:,[1,2]))];
        hist_step_length    = [hist_step_length,mean(c_state(:,5))];
        hist_CE             = [hist_CE,CE];

        % ========
        % 5.5 Reduce the number of particle
        % ========
        if strcmp(param_init{14},'reduced') && pf_reduction_flag && already_reduced == 0
            [c_weights_sorted,sorted_idx ]= sort(c_weights,'descend');
            c_state    = c_state(sorted_idx(1:param_init{8}{4}),:);
            c_weights  = c_weights_sorted(1:param_init{8}{4});
            previous_step_location = previous_step_location(sorted_idx(1:param_init{8}{4}),:);
            already_reduced = 1; % this will make sure that you wont reduced twice
            Kl_first_trigger_step = min([Kl_first_trigger_step,step_count]);
        end

        % plots
        if  (mod(step_count,3)==0) ||  step_count == Kl_first_trigger_step
            particle_plots(param_init,map_lines,c_state,c_weights,step_count,hist_pos_mean,...
                hist_gyro_bias_mean,hist_gyro_bias,hist_heading_mean,hist_CE)
            pause(0.001)
        end

    else

        %  state renaming
        c_state = n_state;

    end

    % 0. true value update
    hist_heading_complete_mean = [hist_heading_complete_mean,mean(c_state(:,3)).*(180/pi)];
    hist_pos_complete_mean     = [hist_pos_complete_mean;mean(c_state(:,[1,2]))];

    % add iteration time
    t_iteration     = t_pf_predict + t_pf_update + t_pf_divergence;
    t_iteration_all = t_iteration_all + t_iteration;
    t_ce_all = t_ce_all + t_ce;
end

%==========================================================================
%----------------------------------Helper----------------------------------
%==========================================================================

% 1. PDR_PF_2D_param_initialization
% 2. PDR_PF_2D_read_CAD
% 3. PDR_PF_2D_gen_occupy
% 4. PDR_PF_2D_init_occupy
% 5. PDR_PF_2D_load_IMU
% 6. PDR_PF_2D_calib_IMU
% 7. PDR_PF_2D_step_detect
% 8. PDR_PF_2D_filt_IMU
% 9. PDR_PF_2D_estimate_length
% 10. PDR_PF_2D_grav_based_att_correction

% particles
% 1. particle_filter_predict
% 2. particle_filter_update1
% 3. particle_filter_update2
% 4. particle_filter_resample
% 5. PDR_PF_2D_CE_test
% 6. particle_plots

%----------------------------------General---------------------------------

% initialize parameters
function param_init = PDR_PF_2D_param_initialization()

% outmost_folder = "C:\__Data\ISPRS_GeoInformation_PDR_EKF\Samsung_A16_Ilyar_2025_05_14_02_14_14"; 
% init_02_IMU_filename        ={'samsung_A16','TotalAcceleration.csv','Gyroscope.csv',[1200,8400]};

% outmost_folder = "C:\__Data\ISPRS_GeoInformation_PDR_EKF\Samsung_A16_Ilyar_2025_06_30_01_28_38"; 
% init_02_IMU_filename        ={'samsung_A16','TotalAcceleration.csv','Gyroscope.csv',[200,7200]};

% outmost_folder = "C:\__Data\ISPRS_GeoInformation_PDR_EKF\Samsung_A16_Ilyar_2025_07_01_00_13_03"; 
% init_02_IMU_filename        ={'samsung_A16','TotalAcceleration.csv','Gyroscope.csv',[200,14600]};

% outmost_folder = "C:\__Data\ISPRS_GeoInformation_PDR_EKF\Samsung_A16_Ilyar_2025_07_06_01_18_53"; 
% init_02_IMU_filename ={'samsung_A16','TotalAcceleration.csv','Gyroscope.csv',[200,7610]}; 

% outmost_folder = "C:\__Data\ISPRS_GeoInformation_PDR_EKF\Samsung_A16_Ilyar_2025_07_22_23_24_44"; 
% init_02_IMU_filename        ={'samsung_A16','TotalAcceleration.csv','Gyroscope.csv',[1,15900]};

outmost_folder = ".\sample_data\Samsung_A16_Ilyar_2025_07_22_01_41_49"; 
init_02_IMU_filename        ={'sensor_logger','TotalAcceleration.csv','Gyroscope.csv',[2,15000]};

init_01_IMU_folder           = outmost_folder + "\navigation\";  % path to data
init_03_calibration_folder   = outmost_folder + "\calibration\"; % path to calibration files
init_04_calibration_filename = {'accel_calib.mat','gyros_calib.mat'}; % accel mat(4 by 4), gyro mat(4 by 4)
init_05_map_information      ={'../../DXF_2_CSV/exported_lines_doorclosed',4.3197,0.01}; % folder path, map scale , grid_resolution

% type of filtering, and parameters
init_06_frequnecy_filtering ={'',''};

% step detection type and parameters
init_07_stepdetectionethod  ={'',''};

% particle fiter parameters
% {par_dim_pos,{maximum_ori_number,range_orientation},{predict_noise_gyro_deg,predict_noise_step_meter}, particle numbers after reduction};
init_08_sampling_size   = {2000,{-91:1:-89},{0.03,0.4},1000}; %

% cross entropy test
init_09_cross_entropy = {1.8,3.0};%{CE heading threshold, CE position threshold}

% filtering
init_11_butterworh_filter = [2,8]; % order, cut-off frequency

% true coordinate reader
init_12_true_coords = outmost_folder + "\ISPRS_GeoInformation_UserTrueCoordinates.xlsx";

% mode
init_13_mode = "PF"; % currently only supports PF

% mode 2
init_14_mode = "reduced"; % "not reduced" "reduced"

% where to save .PDF graphics (saves the particle history and cross entropy)
init_15_figure_save_folder = "C:\Users\ilyar\Desktop\ISPRS_GeoInformation\with_noise\";

% get all the hyperparameters to
param_init=...
    {init_01_IMU_folder,init_02_IMU_filename,init_03_calibration_folder,...
    init_04_calibration_filename,init_05_map_information,...
    init_06_frequnecy_filtering,init_07_stepdetectionethod,...
    init_08_sampling_size,init_09_cross_entropy,[],...
    init_11_butterworh_filter,init_12_true_coords,init_13_mode,init_14_mode,...
    init_15_figure_save_folder};

end

% read map lines
function map_lines = PDR_PF_2D_read_CAD(param_init)

fullPath = param_init{5}{1};

% Read the CSV (skip header row)
map_lines = readmatrix(fullPath);

% Create a new figure or axes
tiledlayout(1,2); % 2 rows, 1 column
fig_ax= nexttile;
% fig_ax = uiaxes(line_fig, 'Position', [50 50 600 500]);
hold(fig_ax, 'on');
axis(fig_ax, 'equal');
title(fig_ax, 'Line Plot from CSV');
xlabel(fig_ax, 'X');
ylabel(fig_ax, 'Y');

% Plot each line
for i = 1:size(map_lines, 1)
    x = [map_lines(i, 1), map_lines(i, 3)];
    y = [map_lines(i, 2), map_lines(i, 4)];
    plot(fig_ax, x, y, 'b');
end
end

% convert map to occupancy grids
function [map_occupy,map_lines_rc,map_lines_h,x_min,y_min]= PDR_PF_2D_gen_occupy(param_init,map_lines)

% Parameters
margin = 2; % margin to add to min/max
resolution = param_init{5}{3}; % grid cell size, the ratio of grid cell to the original
world_scale =param_init{5}{2};

% Step 1: Get world bounds
x_all = [map_lines(:,1); map_lines(:,3)];
y_all = [map_lines(:,2); map_lines(:,4)];
x_min = floor(min(x_all)) - margin;
x_max = ceil(max(x_all)) + margin;
y_min = floor(min(y_all)) - margin;
y_max = ceil(max(y_all)) + margin;

% Step 2: Create grid
cols = ceil((x_max - x_min)/resolution) + 1;
rows = ceil((y_max - y_min)/resolution) + 1;
grid = zeros(rows, cols);

% Step 3: Draw lines into grid
for i = 1:size(map_lines,1)
    x1 = map_lines(i,1);
    y1 = map_lines(i,2);
    x2 = map_lines(i,3);
    y2 = map_lines(i,4);

    % Generate discrete points using linear interpolation
    num_points = max(abs([x2 - x1, y2 - y1])) * 1/resolution;
    x_vals = linspace(x1, x2, num_points);
    y_vals = linspace(y1, y2, num_points);

    % Convert world coordinates to grid indices
    col_idx = round((x_vals - x_min) / resolution) + 1;
    row_idx = round((y_vals - y_min) / resolution) + 1;

    % Ensure indices are within grid bounds
    valid = row_idx > 0 & row_idx <= rows & col_idx > 0 & col_idx <= cols;

    % Mark grid cells as 1
    linear_idx = sub2ind(size(grid), row_idx(valid), col_idx(valid));
    grid(linear_idx) = 1;
end

% Optional: Display grid
% fig_ax = uiaxes(line_fig, 'Position', [600+50 50 600 300]);
fig_ax= nexttile;
hold(fig_ax, 'on');
axis(fig_ax, 'equal');
title(fig_ax, 'Line Plot from CSV');
xlabel(fig_ax, 'X');
ylabel(fig_ax, 'Y');
grid_D = imdilate(grid,ones(4,4));
imagesc(grid_D);
axis equal;
colormap(gray);
title('Discrete World Grid (1 = line cell, 0 = empty)');

% ?
map_occupy = grid_D;

%
% Step 1: Get world bounds
x_all = ([map_lines(:,1), map_lines(:,3)]);
y_all = ([map_lines(:,2), map_lines(:,4)]);
map_lines_rc = map_lines;
map_lines_rc(:,[2,4]) = y_all.*world_scale; % map lines recentered
map_lines_rc(:,[1,3]) = x_all.*world_scale; % map lines recentered

% map homo
map_pt_st = [map_lines_rc(:,[1,2]),ones(size(map_lines_rc,1),1)];
map_pt_ed = [map_lines_rc(:,[3,4]),ones(size(map_lines_rc,1),1)];
map_lines_h = cross(map_pt_st,map_pt_ed);
line_norm =sqrt(sum(map_lines_h(:,1:2).^2,2));
map_lines_normalized(:,1) = map_lines_h(:,1)./line_norm;
map_lines_normalized(:,2) = map_lines_h(:,2)./line_norm;
map_lines_normalized(:,3) = map_lines_h(:,3)./line_norm;
map_lines_h = map_lines_normalized;


end

% initialize particles
function [m_par,map_occupy_filled_for_boundary] = PDR_PF_2D_init_occupy(param_init,map_occupy,x_min,y_min)

global previous_step_location

par_num_pos = param_init{8}{1};
par_num_ori_range = param_init{8}{2}{1};
resolution = param_init{5}{3}; % grid cell size, the ratio of grid cell to the original
world_scale = param_init{5}{2}; % grid cell size, the ratio of grid cell to the original

figure('Name', 'particle show')
map_occupy_filled = imfill(map_occupy);
map_occupy_filled_for_boundary = imerode(map_occupy_filled,ones(5,5)); % remove thin lines
map_occupy_filled_for_boundary = imdilate(map_occupy_filled_for_boundary,ones(9,9)); % fill thin lines
map_occupy_filled_for_boundary = imerode(map_occupy_filled_for_boundary,ones(9,9)); % backstep

% imshow(map_occupymap_occupy_filled_for_boundary_filled)
% map_boundary = bwboundaries(map_occupy_filled_for_boundary);

% hold on
% scatter(map_boundary{1}(:,2),map_boundary{1}(:,1))

% dead margins
dead_margin = 1; % margin around the boundary of the map, not to take particles from
map_occupy_filled = imerode(map_occupy_filled,ones(dead_margin,dead_margin));

map_occupy_filled = map_occupy_filled .* ~map_occupy;
warning('off');imshow(map_occupy_filled);warning('on');
hold on
[X,Y]= meshgrid(1:size(map_occupy_filled,2),1:size(map_occupy_filled,1));
x_flat= X(:);
Y_flat= Y(:);
flattened_idx = map_occupy_filled(:);
X_flat_idx = x_flat(logical(flattened_idx));
Y_flat_idx = Y_flat(logical(flattened_idx));
scatter(X_flat_idx(1:1:end),Y_flat_idx(1:1:end),'Marker','+','LineWidth',5)
hold off

% lim_x_lower = 5;
% lim_x_upper = 10;
% lim_y_lower = 20.0;
% lim_y_upper = 30.0;

lim_x_lower = -inf;
lim_x_upper =  inf;
lim_y_lower = -inf;
lim_y_upper =  inf;

% downsample original particle
par_pos = [X_flat_idx,Y_flat_idx];
par_pos_downsampled = par_pos;
gridSize  = 0.1;
while size(par_pos_downsampled,1) > par_num_pos
 
    gridSize = gridSize + 0.1;
    x_bins = floor(par_pos_downsampled(:,1) / gridSize);
    y_bins = floor(par_pos_downsampled(:,2) / gridSize);
    bin_keys = x_bins + y_bins * 1e5;  % assuming no collisions
    [~, unique_idx] = unique(bin_keys);
    par_pos_downsampled = par_pos_downsampled(unique_idx, :);
    
    condition1 = (par_pos_downsampled(:,1) .* resolution + x_min).* world_scale > lim_x_lower;
    condition2 = (par_pos_downsampled(:,1) .* resolution + x_min).* world_scale < lim_x_upper;
    condition3 = (par_pos_downsampled(:,2) .* resolution + y_min).* world_scale > lim_y_lower;
    condition4 = (par_pos_downsampled(:,2) .* resolution + y_min).* world_scale < lim_y_upper;

    par_pos_downsampled = par_pos_downsampled(condition1 & condition2 & condition3 & condition4 , :);
end
par_pos = par_pos_downsampled;

ori_degrees = par_num_ori_range;
ori_radians = ori_degrees * (pi/180);

par_pos_all = repmat(par_pos,length(ori_radians),1);
par_ori_all = repmat(ori_radians,size(par_pos,1),1);
par_ori_all = par_ori_all(:);
par_pos_all(:,1) = par_pos_all(:,1) .* resolution + x_min; % change bck the coordinate from image to point
par_pos_all(:,2) = par_pos_all(:,2) .* resolution + y_min; % change bck the coordinate from image to point
par_pos_all = par_pos_all .* world_scale; % scale the points to map scale
m_par = [par_pos_all,par_ori_all];
m_par = [m_par, zeros(size(m_par,1),1),zeros(size(m_par,1),1)]; % bias in the heading and bias in the step size
previous_step_location = m_par;

end

% read IMU data
function IMU_all = PDR_PF_2D_load_IMU(param_init)

% type of data that is assumed to Sensor Log for A16
init_01_IMU_folder       =  param_init{1};
init_02_accel_filename   = param_init{2}{2};
init_02_gyro_filename    = param_init{2}{3};
init_02_IMU_skip_initial = param_init{2}{4}(1);
init_02_IMU_skip_end     = param_init{2}{4}(2);
fullpathname_accel       = strcat(init_01_IMU_folder,init_02_accel_filename);
fullpathname_gyro        = strcat(init_01_IMU_folder,init_02_gyro_filename);

% Accel RSeadings
accel_xyz= readmatrix(fullpathname_accel);
gyros_xyz= readmatrix(fullpathname_gyro);
gyros_xyz(:,3:5) = gyros_xyz(:,3:5).*(180/pi);
figure('Name','IMU uncalibrated')
tiledlayout(2,3)
a_x = nexttile;
plot(a_x,accel_xyz(:,1),accel_xyz(:,3),'Color','r','DisplayName','Accel X');grid on; grid minor;xlabel('time');ylabel('a(m/s^2)');legend("show");
a_y = nexttile;
plot(a_y,accel_xyz(:,1),accel_xyz(:,4),'Color','b','DisplayName','Accel Y');grid on; grid minor;xlabel('time');ylabel('a(m/s^2)');legend("show");
a_z = nexttile;
plot(a_z,accel_xyz(:,1),accel_xyz(:,5),'Color','g','DisplayName','Accel Z');grid on; grid minor;xlabel('time');ylabel('a(m/s^2)');legend("show");
a_all = [a_x,a_y,a_z];
for a_index = 1:length(a_all)
    a = a_all(a_index);
    a.GridLineWidth = 1;
    a.GridAlpha = 0.3;
    a.FontSize= 15;
    a.FontWeight = 'bold';
    a.MinorGridLineWidth = 0.5;
    a.FontName= 'Times';
end

% Gyroscope Readings
a_x = nexttile;
plot(a_x,gyros_xyz(:,1),gyros_xyz(:,3),'Color','r','DisplayName','Gyro X');grid on; grid minor;xlabel('time');ylabel('\Omega (\circ/s)');legend("show");
a_y = nexttile;
plot(a_y,gyros_xyz(:,1),gyros_xyz(:,4),'Color','b','DisplayName','Gyro Y');grid on; grid minor;xlabel('time');ylabel('\Omega (\circ/s)');legend("show");
a_z = nexttile;
plot(a_z,gyros_xyz(:,1),gyros_xyz(:,5),'Color','g','DisplayName','Gyro Z');grid on; grid minor;xlabel('time');ylabel('\Omega (\circ/s)');legend("show");

% beautify axes
a_all = [a_x,a_y,a_z,a_all];
for a_index = 1:length(a_all)
    a = a_all(a_index);
    a.GridLineWidth = 1;
    a.GridAlpha = 0.3;
    a.FontSize= 15;
    a.FontWeight = 'bold';
    a.MinorGridLineWidth = 0.5;
    a.FontName= 'Times';
end
legend('show')

% link axes
linkaxes(a_all,'x')

% interpolate accelerometer to follow gyrosocpe
gyros_x_interp = interp1(gyros_xyz(:,1),gyros_xyz(:,3),accel_xyz(:,1)); % gyro x
gyros_y_interp = interp1(gyros_xyz(:,1),gyros_xyz(:,4),accel_xyz(:,1)); % gyro y
gyros_z_interp = interp1(gyros_xyz(:,1),gyros_xyz(:,5),accel_xyz(:,1)); % gyro z
gyros_xyz_i= [gyros_x_interp,gyros_y_interp,gyros_z_interp];
IMU_all = [accel_xyz(:,1),accel_xyz(:,3:5),gyros_xyz_i];

% skip the data
IMU_all = IMU_all(init_02_IMU_skip_initial:init_02_IMU_skip_end,:);

% IMU time in seconds
IMU_all(:,1) = IMU_all(:,1) * 10^-9;

end

% calibrate IMU data
function IMU_calib = PDR_PF_2D_calib_IMU(param_init,IMU_all)


% fetch path
calibration_folder = param_init{3};
accel_calibration_file = param_init{4}{1};
gyro_calibration_file = param_init{4}{2};
accel_calibration_file = fullfile(calibration_folder,accel_calibration_file);
gyros_calibration_file = fullfile(calibration_folder,gyro_calibration_file);

% read calibration
load(accel_calibration_file);
load(gyros_calibration_file);

% apply calibration
D = blkdiag(accel_H_mat, gyros_H_mat);
IMU_calibrated_w_dummy_col= (D * [IMU_all(:,2:4),ones(size(IMU_all,1),1),IMU_all(:,5:7),ones(size(IMU_all,1),1)]')';
IMU_calibrated_w_dummy_col(:,[4,8]) = [];
IMU_calib =[IMU_all(:,1),IMU_calibrated_w_dummy_col];

% calibration before and after
disp(strcat("gravity magnitude before the calibration was: " ,num2str(mean(sqrt(sum((IMU_all(1:50,2:4)).^2,2))))))
disp(strcat("gravity magnitude after the calibration was: "  ,num2str(mean(sqrt(sum((IMU_calib(1:50,2:4)).^2,2))))))

% Accel RSeadings
figure('Name','IMU calibrated')
tiledlayout(2,3)
a_x = nexttile;
plot(a_x,IMU_calib(:,1),IMU_calib(:,2),'Color','r','DisplayName','Accel X');grid on; grid minor;xlabel('time');ylabel('a(m/s^2)');legend("show");
a_y = nexttile;
plot(a_y,IMU_calib(:,1),IMU_calib(:,3),'Color','b','DisplayName','Accel Y');grid on; grid minor;xlabel('time');ylabel('a(m/s^2)');legend("show");
a_z = nexttile;
plot(a_z,IMU_calib(:,1),IMU_calib(:,4),'Color','g','DisplayName','Accel Z');grid on; grid minor;xlabel('time');ylabel('a(m/s^2)');legend("show");
a_all = [a_x,a_y,a_z];
for a_index = 1:length(a_all)
    a = a_all(a_index);
    a.GridLineWidth = 1;
    a.GridAlpha = 0.3;
    a.FontSize= 15;
    a.FontWeight = 'bold';
    a.MinorGridLineWidth = 0.5;
    a.FontName= 'Times';
end

% Gyroscope Readings
a_x = nexttile;
plot(a_x,IMU_calib(:,1),IMU_calib(:,5),'Color','r','DisplayName','Gyro X');...
    grid on; grid minor;xlabel('time');ylabel('\Omega (\circ/s)');legend("show");
a_y = nexttile;
plot(a_y,IMU_calib(:,1),IMU_calib(:,6),'Color','b','DisplayName','Gyro Y');...
    grid on; grid minor;xlabel('time');ylabel('\Omega (\circ/s)');legend("show");
a_z = nexttile;
plot(a_z,IMU_calib(:,1),IMU_calib(:,7),'Color','g','DisplayName','Gyro Z');...
    grid on; grid minor;xlabel('time');ylabel('\Omega (\circ/s)');legend("show");

% beautify axes
a_all = [a_x,a_y,a_z,a_all];
for a_index = 1:length(a_all)
    a = a_all(a_index);
    a.GridLineWidth = 1;
    a.GridAlpha = 0.3;
    a.FontSize= 15;
    a.FontWeight = 'bold';
    a.MinorGridLineWidth = 0.5;
    a.FontName= 'Times';
end
legend('show')

% link axes
linkaxes(a_all,'x')

end

% detect steps
function [peak_indices,peak_values,low_indices,low_values,accel_v_all,ver_dir] = PDR_PF_2D_step_detect(param_init,IMU_all_calib)

% estimate the frequency in the data
imu_time_sec = IMU_all_calib(:,1);
imu_time_diff_sec= diff(imu_time_sec);
mean_freq= 1/mean(imu_time_diff_sec);
window_size = floor(mean_freq) - floor(mean_freq)/2; % change the second term if steps are being missed

% estimate the roll and pitch values
% track the gravity vector in the sensor frame
accel =IMU_all_calib(:,2:4);
gyros =IMU_all_calib(:,5:7);
gyros_radians = gyros * (pi/180);
imu_datanum = size(accel,1);

% estimate initial gravity vector
user_pause = param_init{2}{4}(1);
accel_mean = mean(accel(1:user_pause,:));
g_vec_initial_frame = accel_mean';
initial_rotation_matrix = eye(3);
R_k = initial_rotation_matrix;

% estimate an approximate vertical direction
[~,ver_idx] = max(abs(accel_mean));
if ver_idx == 1
    ver_dir = [1,0,0];
elseif ver_idx == 2
    ver_dir = [0,1,0];
else
    ver_dir = [0,0,1];
end

% IMU time
g_v_all = [];
accel_v_all = [];
for i = 1:imu_datanum -1

    % build the matrix exponent
    omega = gyros_radians(i,:);
    delta_omega_t = imu_time_diff_sec(i) .* omega;

    % matrix skew-symmetric
    delta_omega_t_X = [0 -delta_omega_t(3)  delta_omega_t(2) ;
        delta_omega_t(3)     0 -delta_omega_t(1) ;
        -delta_omega_t(2)  delta_omega_t(1)     0 ];

    delta_R= expm(delta_omega_t_X);
    R_k = delta_R * R_k; % this will become the total rotation
    R_k_T = R_k';
    g_vec_current_frame = R_k_T * g_vec_initial_frame;

    % current accel in locl level frame
    a_vec_current_frame = R_k * accel(i,1:3)';

    % project g_vec_current_frame to vertical
    dot_prodcut = dot(g_vec_current_frame,ver_dir');
    vertial_deviation = acosd(dot_prodcut/(norm(g_vec_current_frame)*1));
    vertical_component = cosd(vertial_deviation) * g_vec_current_frame;
    g_v_all = [g_v_all,vertical_component(2)]; % what is 2 here?

    % project a_vec_current_frame to vertical
    dot_prodcut = dot(a_vec_current_frame,ver_dir');
    accel_v_all = [accel_v_all,dot_prodcut];
end

% estimate the magnitude of the accelerometer
halfWin = floor(window_size / 2);
N = length(accel_v_all);

% estimate peak and vally in the vertical direction
peak_indices = [];
peak_values = [];
low_indices = [];
low_values = [];
for i = (1 + halfWin):(N - halfWin)
    window = accel_v_all((i - halfWin):(i + halfWin));
    centerValue = accel_v_all(i);
    centerValue_a = accel_v_all(i);

    if centerValue == max(window) && abs(centerValue-9.81) > .5
        peak_indices(end + 1) = i;
        peak_values(end + 1) = centerValue_a;
    end
end
for i = (1 + halfWin):(N - halfWin)
    window = accel_v_all((i - halfWin):(i + halfWin));
    centerValue = accel_v_all(i);
    centerValue_a = accel_v_all(i);

    if centerValue == min(window) && abs(centerValue-9.81) > .5
        low_indices(end + 1) = i;
        low_values(end + 1) = centerValue_a;
    end
end

figure ('name','detected peaks');
plot(imu_time_sec(1:end-1), accel_v_all);
hold on;
plot(imu_time_sec(peak_indices), peak_values, 'v', 'MarkerFaceColor', 'r');
plot(imu_time_sec(low_indices), low_values, '^', 'MarkerFaceColor', 'g');

xlabel('Time (s)');
ylabel('Acceleration');
title('Peak Acceleration Detection');
legend('Acceleration', 'Detected Peaks');
grid on;

disp(strcat("[INFO] Detected number of peaks: ", ...
    num2str(length(low_indices)), ...
    " Detected number of vallies: ",...
    num2str(length(peak_indices))))
disp(strcat('[Warning]'," Number of peaks and vallies are different by: ", ...
    num2str(abs(length(low_indices)-length(peak_indices)))))

end

% filter IMU data
function  IMU_all_filt = PDR_PF_2D_filt_IMU(param_init,IMU_all_calib)

%  set the order and the cut-off frequnecy utilized
order  = param_init{11}(1);
cutoff = param_init{11}(2);

% estimate the frequency in the data
imu_time_sec = IMU_all_calib(:,1);
imu_time_diff_sec= diff(imu_time_sec);
mean_freq= 1/mean(imu_time_diff_sec);

% Butterworth Low-Pass Filter Design
Wn = cutoff / (mean_freq/2);    % Normalize frequency (Nyquist)

[b, a] = butter(order, Wn, 'low');  % Low-pass Butterworth filter

% create filtered data mat
IMU_all_filt= zeros(size(IMU_all_calib,1),7);
IMU_all_filt(:,1) = IMU_all_calib(:,1);

% Apply the filter
y = filtfilt(b, a, IMU_all_calib(:,2));   % Zero-phase filtering
IMU_all_filt(:,2) = y;

% Apply the filter
y = filtfilt(b, a, IMU_all_calib(:,3));   % Zero-phase filtering
IMU_all_filt(:,3) = y;

% Apply the filter
y = filtfilt(b, a, IMU_all_calib(:,4));   % Zero-phase filtering
IMU_all_filt(:,4) = y;

% Apply the filter
y = filtfilt(b, a, IMU_all_calib(:,5));   % Zero-phase filtering
IMU_all_filt(:,5) = y;

% Apply the filter
y = filtfilt(b, a, IMU_all_calib(:,6));   % Zero-phase filtering
IMU_all_filt(:,6) = y;

% Apply the filter
y = filtfilt(b, a, IMU_all_calib(:,7));   % Zero-phase filtering
IMU_all_filt(:,7) = y;
end

% detect step lengths
function [step_leng,step_leng_flagged] = PDR_PF_2D_estimate_length(param_init,IMU_all_calib,accel_v_all,peak_indices,low_indices)

% estimate the step-lengt
k = 0.42;
num_steps = length(peak_indices);
step_leng = zeros(1,num_steps);

% just keep the indices that are closest
clst_indices = knnsearch(low_indices',peak_indices');
low_indices = low_indices(clst_indices);

for i = 1:num_steps
    Amax = accel_v_all(peak_indices(i));
    Amin = accel_v_all(low_indices(i));
    step_leng(i) = k * (Amax - Amin)^(1/4);
end

% estimat the median step size (for plotting purposes)
step_leng_median = median(step_leng);

% convert indictes to flag-array
step_leng_flagged = zeros(1,size(IMU_all_calib,1));
step_leng_flagged(peak_indices(1:end))=step_leng;

% Create figure with two subplots side-by-side
warning('off');figure('Name', 'Step length Overview', 'Position', [100, 100, 1000, 400]);warning('on');

% Time series plot
subplot(1, 2, 1);
plot(step_leng, 'b');
xlabel('Time (s)');
ylabel('Acceleration (m/s^2)');
title('Acceleration Time Series');
grid on;

% histogram Plot ---
subplot(1, 2, 2);
h = histogram(step_leng, 'BinMethod', 'fd', 'FaceColor', [0.2 0.6 0.8]);
hold on;
yl = ylim;
plot([step_leng_median step_leng_median], yl, 'r--', 'LineWidth', 2);  % Median line
hold off;
xlabel('step_leng(m)');
ylabel('Count');
title('step_leng Distribution (Histogram)');
legend('Histogram', 'Median', 'Location', 'best');
grid on;

end

% correct gyroscope heading in the local level frame
function c_ang_rates= PDR_PF_2D_grav_based_att_correction(param_init,c_IMU,ver_dir)

% fetch data
accel_data = c_IMU(2:4);
gyro_data = c_IMU(5:7);

% estimate the relative orienation between them
dot_value     = dot(accel_data, ver_dir);
cross_vec     = cross(accel_data, ver_dir);
cross_value   = norm(cross_vec);
ang_delta     = atan2(cross_value,dot_value);
axesangle     = [cross_vec,ang_delta];
rotm_m        = axang2rotm(axesangle);
vertical_gyro = rotm_m * gyro_data';
c_ang_rates   = vertical_gyro(logical(ver_dir));

end



%----------------------------particle filter ------------------------------

% update the position +add noise(sampling)
function [n_state, delta_v_homo,delta_v_lines] = particle_filter_predict(param_init,c_state,c_ang_rates,delta_t_seconds,step_flag,step_leng)

global previous_step_location

% draw noise given the covariance matrix
omega_z_deg_std = param_init{8}{3}{1};
step_length_std = param_init{8}{3}{2};

% generate random normal noise error in omega_z
rand_additive_noise = randn(size(c_state,1),1);
rand_omega_z_noise = rand_additive_noise * omega_z_deg_std;

% generate random normal noise error in omega_z
rand_additive_noise = randn(size(c_state,1),1);
step_length_noise = rand_additive_noise * step_length_std;

n_state = c_state;

% mechanize attitude
for i= 1:size(c_state,1)

    perv_gyro_bias = c_state(i,4);
    perv_step_bias = c_state(i,5);
    current_gyro_bias = rand_omega_z_noise(i) + perv_gyro_bias;
    current_step_bias = step_length_noise(i) + perv_step_bias;
    n_state(i,4) = current_gyro_bias;
    n_state(i,5) = current_step_bias;

    gyro_w_noise = c_ang_rates + current_gyro_bias;
    omega = gyro_w_noise * (pi/180);

    % build the matrix exponent
    delta_omega_t = delta_t_seconds * omega;

    % % mechanize
    n_state(i,3) = c_state(i,3) + delta_omega_t ;

    if step_flag == 1
        % delta_v =n_state_mat(:,(i-1)*3+1:i*3) * [step_leng + step_length_noise(i);0;0];
        n_state(i,5) = step_leng + step_length_noise(i);
        delta_v(1) = cos(n_state(i,3)) * (step_leng + step_length_noise(i));
        delta_v(2) = sin(n_state(i,3)) * (step_leng + step_length_noise(i));
        n_state(i,1:2) = delta_v([1,2]) + c_state(i,[1,2]);
    else
        % the code below is correct, but moved up to avoid consuming
        % computation power
        % n_state(i,3) = c_state(i,3);
    end
end

if step_flag == 1
    % now homogenous coordinates of the lines for each of ponts above
    delta_v_lines = [n_state(:,1:2),previous_step_location(:,1:2)];

    n_state_homo = [n_state(:,1:2),ones(size(n_state,1),1)];
    p_state_homo = [previous_step_location(:,1:2),ones(size(previous_step_location,1),1)];
    delta_V_lines_h = cross(n_state_homo,p_state_homo);
    line_norm =sqrt(sum(delta_V_lines_h(:,1:2).^2,2));
    delta_v_homo(:,1) = delta_V_lines_h(:,1)./line_norm;
    delta_v_homo(:,2) = delta_V_lines_h(:,2)./line_norm;
    delta_v_homo(:,3) = delta_V_lines_h(:,3)./line_norm;
    previous_step_location = n_state;
else
    delta_v_homo = [];
    delta_v_lines= [];
end

end

% weight particles based on whether they fall outside of the map or
function n_weights = particle_filter_update1(param_init,map_lines,map_lines_h,delta_v_lines,delta_v_homo,c_weights)

% map limits
limits_x_max  = max([map_lines(:,1),map_lines(:,3)]')';
limits_x_min  = min([map_lines(:,1),map_lines(:,3)]')';
limits_y_max  = max([map_lines(:,2),map_lines(:,4)]')';
limits_y_min  = min([map_lines(:,2),map_lines(:,4)]')';

limits_x1_max = max([delta_v_lines(:,1),delta_v_lines(:,3)]')';
limits_x1_min = min([delta_v_lines(:,1),delta_v_lines(:,3)]')';
limits_y1_max = max([delta_v_lines(:,2),delta_v_lines(:,4)]')';
limits_y1_min = min([delta_v_lines(:,2),delta_v_lines(:,4)]')';

th = 0.3;

% find the intersection of delta_v_homo with map_lines_h
inside_flag = zeros(1,length(c_weights));
% all_intersections = [];
for i=1:length(c_weights)

    % intersect the motion line with all the map lines
    delta_v_homo_rep = repmat(delta_v_homo(i,:),size(map_lines_h,1),1);
    int_pt = cross(delta_v_homo_rep,map_lines_h);
    int_pt(:,1) = int_pt(:,1)./int_pt(:,3);
    int_pt(:,2) = int_pt(:,2)./int_pt(:,3);
    int_pt(:,3) = int_pt(:,3)./int_pt(:,3);
    
    % check if the intersection is inside the motion line and the map line
    x_inside_check  = int_pt(:,1) < (limits_x_max + th) & int_pt(:,1) > (limits_x_min - th);
    y_inside_check  = int_pt(:,2) < (limits_y_max + th) & int_pt(:,2) > (limits_y_min - th);
    x1_inside_check = int_pt(:,1) < (limits_x1_max(i) + th) & int_pt(:,1) > (limits_x1_min(i) - th);
    y1_inside_check = int_pt(:,2) < (limits_y1_max(i) + th) & int_pt(:,2) > (limits_y1_min(i) - th);
    xy_inside_check = x_inside_check & y_inside_check & x1_inside_check & y1_inside_check;
    inside_flag(i)  = sum(xy_inside_check) > 0;

    % Store intersection points for plotting
    % all_intersections = [all_intersections; int_pt(xy_inside_check,1:2)];

end

%=================================================
% --------------------- Plots---------------------
%=================================================
x_zoom = [-5,5];
y_zoom = [-5,5];

% figure; 
% axes_handle = axes();
% hold on;
% h1 = line([map_lines(:, 1), map_lines(:, 3)]', [map_lines(:, 2), map_lines(:, 4)]','color','blue','LineWidth', 1.5,'DisplayName','map lines');
% h2 = line([delta_v_lines(:, 1), delta_v_lines(:, 3)]', [delta_v_lines(:, 2), delta_v_lines(:, 4)]','LineWidth', 1.5,'color','red');
% % scatter(delta_v_lines(:, 1), delta_v_lines(:, 2),'MarkerEdgeColor','flat','Marker','.','Color',[0.5,0.5,0]);
% % scatter(delta_v_lines(:, 3), delta_v_lines(:, 4),'MarkerEdgeColor','flat','Marker','.','Color',[0,0,0]);
% h3= plot(all_intersections(:,1), all_intersections(:,2), 'ko', 'MarkerSize', 6, 'MarkerFaceColor', 'g');
% axes_handle.GridLineWidth = 1;
% axes_handle.GridAlpha = 0.3;
% axes_handle.FontSize= 18;
% axes_handle.FontWeight = 'bold';
% axes_handle.MinorGridLineWidth = 0.5;
% axes_handle.FontName= 'Times';
% grid on; grid minor;
% xlabel('X (meters)');
% ylabel("Y (meters)");
% axis equal;
% rectangle('Position', [x_zoom(1), y_zoom(1), diff(x_zoom), diff(y_zoom)], ...
%           'EdgeColor', [0,0,0], 'LineWidth', 4);
% legend([h1(1),h2(1),h3(1)], {'map lines','user motion','intersections'});
% 
% % zoomed plot
% axes_zoomed_handle = axes('Position', [0.58 0.3 0.25 0.25]); % adjust as needed
% box on; % draw box around inset
% line([map_lines(:, 1), map_lines(:, 3)]', [map_lines(:, 2), map_lines(:, 4)]','color','blue','LineWidth', 1.5,'DisplayName','map lines');
% hold on
% line([delta_v_lines(:, 1), delta_v_lines(:, 3)]', [delta_v_lines(:, 2), delta_v_lines(:, 4)]','LineWidth', 1.5,'color','red');
% scatter(delta_v_lines(:, 1), delta_v_lines(:, 2),'MarkerEdgeColor','flat','Marker','.','Color',[0.5,0.5,0]);
% scatter(delta_v_lines(:, 3), delta_v_lines(:, 4),'MarkerEdgeColor','flat','Marker','.','Color',[0,0,0]);
% plot(all_intersections(:,1), all_intersections(:,2), 'ko', 'MarkerSize', 6, 'MarkerFaceColor', 'g');
% axes_zoomed_handle.GridLineWidth = 1;
% axes_zoomed_handle.GridAlpha = 0.3;
% axes_zoomed_handle.FontSize= 18;
% axes_zoomed_handle.FontWeight = 'bold';
% axes_zoomed_handle.MinorGridLineWidth = 0.5;
% axes_zoomed_handle.FontName= 'Times';
% xlabel('X (meters)');
% ylabel("Y (meters)");
% xlim(x_zoom);
% ylim(y_zoom);
% grid on; grid minor;axis equal;
% title('zoomed')
% hold off
% exportgraphics(gcf,"C:\Users\ilyar\Desktop\ISPRS_GeoInformation\Fig2_MDPIISPRS_userpathintersection.pdf","ContentType",'Vector')

% update the weights(1)
update_weight = ones(1,length(c_weights));
update_weight(logical(inside_flag) == 1) = 0.3;

% % find the closest valus to the wall.
% [~,dist]= knnsearch(delta_v_lines(update_weight == 0.3,3:4),delta_v_lines(:,[3,4]));
% normalized_dist = dist./max(dist);
% update_weight = normalized_dist';
% % update_weight(logical(inside_flag) == 1) = 0.3;
% % update_weight = update_weight./sum(update_weight);

n_weights =c_weights .* update_weight;
n_weights =n_weights./sum(n_weights);

end

% weight particles based on whether they fall outside of the map or
function n_weights2 = particle_filter_update2(param_init,c_state,n_weights1,boundary_test_map,boundary_test_flat,x_min,y_min)

resolution     = param_init{5}{3}; % grid cell size, the ratio of grid cell to the original
world_scale    = param_init{5}{2};
PDF_graph_path = param_init{15};

% rescale
c_state_rescaled = c_state(:,1:2) ./ world_scale;
c_state_rescaled(:,1) = (c_state_rescaled(:,1) - x_min)./resolution;
c_state_rescaled(:,2) = (c_state_rescaled(:,2) - y_min)./resolution;

% test
c_state_rescaled = floor(c_state_rescaled);
multiplier = size(boundary_test_map,1);
c_state_encoded_idx = c_state_rescaled(:,2) + (c_state_rescaled(:,1)-1) * multiplier;
out_test = ~boundary_test_flat(c_state_encoded_idx);

% update weights
n_weights1(out_test) = 0.001 .* n_weights1(out_test);
n_weights2 = n_weights1./sum(n_weights1);

end

% resample
function [r_state, r_weight] = particle_filter_resample(n_state,n_weights)

global hist_gyro_bias
global previous_step_location

% SYSTEMATIC_RESAMPLE - Systematic resampling from the given weights.

N = length(n_weights);   % Number of particles
cdf = cumsum(n_weights); % Cumulative distribution
r = rand(1, N); % Generate N uniform random numbers
indices = arrayfun(@(x) find(cdf >= x, 1, 'first'), r);% Resample indices

% resample particles, corresponding weights and step direction
r_state  = n_state(indices,:);
r_weight_unweighted = n_weights(indices);
r_weight = r_weight_unweighted./sum(r_weight_unweighted);
previous_step_location = previous_step_location(indices,:);

% choose the corresponding history of the bias (for plotting purposes)
if ~isempty(hist_gyro_bias)
    hist_gyro_bias = hist_gyro_bias(indices,:);
else
    % pass
end
hist_gyro_bias = [hist_gyro_bias,r_state(:,4)];

end

% check cross entropy
function [pf_reduction_flag,CE_pos] = PDR_PF_2D_CE_test(param_init,r_state,r_weight)

% fetch thresholds for cross entropy
CE_heading_threshold = param_init{9}{1};
CE_posisti_threshold = param_init{9}{2};

% estimate cross entropy for position
gaussian_mean   =  sum(r_weight'.*r_state(:,1:2));
% gaussian_mean =  mean(r_state(:,1:2));
gaussian_cov = 2*eye(2); % target distribution Gaussian's covariance (magic number)
log_pdf_gauss = @(x) -0.5 * sum((x - gaussian_mean) / gaussian_cov .* (x - gaussian_mean), 2) ...
    - 0.5 * log(det(2*pi*gaussian_cov));
CE_pos = -sum(r_weight' .* log_pdf_gauss(r_state(:,[1,2]))); % Emprical cross entropy

% estimate cross entropy for attitude
gaussian_mean = mean(r_state(:,3));
gaussian_cov  = 2.*eye(1); % target distribution Gaussian's covariance (magic number)
log_pdf_gauss = @(x) -0.5 * sum((x - gaussian_mean) / gaussian_cov .* (x - gaussian_mean), 2) ...
    - 0.5 * log(det(2*pi*gaussian_cov));
CE_heading = -sum(r_weight' .* log_pdf_gauss(r_state(:,3))); % Empirical Cross entropy

% CROSS ENTROPY test threshold
if CE_heading < CE_heading_threshold &&  CE_pos < CE_posisti_threshold
    pf_reduction_flag = 1;
else
    pf_reduction_flag = 0;
end

end

% particle plots
function particle_plots(param_init,map_lines,c_state,c_weights,step_count,...
    hist_pos_mean,hist_gyro_bias_mean,hist_gyro_bias,hist_heading_mean,hist_KL)

global Kl_first_trigger_step
PDF_graph_path = param_init{15};
global gif_filename;
reduced_mode        = param_init{14};
sampling_size       = param_init{8}{1};
gyro_bias_noise     = param_init{8}{3}{1};
step_length_noise   = param_init{8}{3}{2};
particle_reduced    = param_init{8}{4};
particle_multiplier = length(param_init{8}{2}{1});
config = strcat("Mode_",reduced_mode,"_","ParNumInit",num2str(sampling_size * particle_multiplier),"_",...
    "GyroBias",num2str(gyro_bias_noise),"_","StepLengthNoise",num2str(step_length_noise),...
    "ParReduced",num2str(particle_reduced));


h= figure('Name','Particle');
axesHandle1 = subplot(1,2,1,'Parent', h);
hold(axesHandle1, 'off');
line([map_lines(:, 1), map_lines(:, 3)]', [map_lines(:, 2), map_lines(:, 4)]',...
    'Parent',axesHandle1,'color','blue');
hold(axesHandle1, 'on');
scatter(c_state(:,1), c_state(:,2),12,c_weights,'Marker','.',...
    'LineWidth',2,'Parent',axesHandle1);
colormap(axesHandle1, 'jet');
c_state_mean = mean(c_state,1);
scatter(c_state_mean(1), c_state_mean(2), 4,'marker','*','linewidth',25, ...
    'Parent',axesHandle1,'DisplayName','mean position');
if step_count > 50
    plot(hist_pos_mean(50:end,1), hist_pos_mean(50:end,2),...
        'Parent',axesHandle1,'DisplayName','mean trajectory');
end
colorbar
hold(axesHandle1, 'off'); % Ensure multiple lines can be drawn
axesHandle1.GridLineWidth = 1;
axesHandle1.GridAlpha = 0.3;
axesHandle1.FontSize= 10;
axesHandle1.FontWeight = 'bold';
axesHandle1.MinorGridLineWidth = 0.5;
axesHandle1.FontName= 'Times';
grid on; grid minor;
xlabel('X (meters)')
ylabel("Y (meters)")
% legend('show')

% % Gyro bias
% axesHandle2 = subplot(1,2,2,'Parent', h);
% % plot(hist_gyro_bias(1:50:end,:)','Parent',axesHandle2,'Color','blue');
% hold(axesHandle2, 'on');
% plot(hist_gyro_bias_mean,'Parent',axesHandle2,'LineWidth',3,'Color','red');
% hold(axesHandle2, 'off');
% axesHandle2.GridLineWidth = 1;
% axesHandle2.GridAlpha = 0.3;
% axesHandle2.FontSize= 10;
% axesHandle2.FontWeight = 'bold';
% axesHandle2.MinorGridLineWidth = 0.5;
% axesHandle2.FontName= 'Times';
% grid on; grid minor;
% xlabel('step count')
% ylabel("gyro z bias (\circ/s)")

% axesHandle3 = subplot(2,2,4,'Parent', h);
% hold(axesHandle3, 'on');
% plot(hist_heading_mean,'Parent',axesHandle3,'LineWidth',3,'Color','red');
% hold(axesHandle3, 'off');
% axesHandle3.GridLineWidth = 1;
% axesHandle3.GridAlpha = 0.3;
% axesHandle3.FontSize= 10;
% axesHandle3.FontWeight = 'bold';
% axesHandle3.MinorGridLineWidth = 0.5;
% axesHandle3.FontName= 'Times';
% grid on; grid minor;
% xlabel('step count')
% ylabel("heading (\circ)")
 
axesHandle4 = subplot(1,2,2,'Parent', h);  % Create new axes in the same figure
plot(hist_KL,'Parent',axesHandle4,'Color','blue');
hold on;
xline(Kl_first_trigger_step,"Label",strcat("particle reduction triggred : ", ...
    num2str(Kl_first_trigger_step)),'linestyle','-.','linewidth',2,...
    "FontSize",8,"FontName","Times","LabelOrientation","horizontal")
hold off
axesHandle4.GridLineWidth = 1;
axesHandle4.GridAlpha = 0.3;
axesHandle4.FontSize= 10;
axesHandle4.FontWeight = 'bold';
axesHandle4.MinorGridLineWidth = 0.5;
axesHandle4.FontName= 'Times';
grid on; grid minor;
xlabel('step count')
ylabel("Cross Entropy")

% % Capture the current figure as an image
% frame = getframe(gcf);
% img = frame2im(frame);
% % img = imresize(img, [1080 1920]);
% img = imresize(img, [1080/2 1920/2]);
% [A, map] = rgb2ind(img, 256);
% 
% 
% % Write to GIF file
% try
%     imwrite(A, map, gif_filename, 'gif', 'WriteMode', 'append', 'DelayTime', 0.25);
% catch
%     % Get current date and time
%     t = datetime('now', 'Format', 'yyyyMMdd_HHmmss');
%     timeStr = char(t);
%     gif_filename = strjoin([timeStr,"_",string(config),'.gif'],"");
%     imwrite(A, map, gif_filename, 'gif', 'LoopCount', Inf, 'DelayTime', 0.25);
% end

end

/*
 * Copyright Cypress Semiconductor Corporation, 2014-2018 All rights reserved.
 *
 * This software, associated documentation and materials ("Software") is
 * owned by Cypress Semiconductor Corporation ("Cypress") and is
 * protected by and subject to worldwide patent protection (UnitedStates and foreign), United States copyright laws and international
 * treaty provisions. Therefore, unless otherwise specified in a separate license agreement between you and Cypress, this Software
 * must be treated like any other copyrighted material. Reproduction,
 * modification, translation, compilation, or representation of this
 * Software in any other form (e.g., paper, magnetic, optical, silicon)
 * is prohibited without Cypress's express written permission.
 *
 * Disclaimer: THIS SOFTWARE IS PROVIDED AS-IS, WITH NO WARRANTY OF ANY
 * KIND, EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO,
 * NONINFRINGEMENT, IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE. Cypress reserves the right to make changes
 * to the Software without notice. Cypress does not assume any liability
 * arising out of the application or use of Software or any product or
 * circuit described in the Software. Cypress does not authorize its
 * products for use as critical components in any products where a
 * malfunction or failure may reasonably be expected to result in
 * significant injury or death ("High Risk Product"). By including
 * Cypress's product in a High Risk Product, the manufacturer of such
 * system or application assumes all risk of such use and in doing so
 * indemnifies Cypress against all liability.
 *
 * Use of this Software may be limited by and subject to the applicable
 * Cypress software license agreement.
 *
 *
 */

package com.cypress.cysmart.CommonFragments;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.view.ViewPager;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.cypress.cysmart.BLEConnectionServices.BluetoothLeService;
import com.cypress.cysmart.CommonUtils.Constants;
import com.cypress.cysmart.CommonUtils.GattAttributes;
import com.cypress.cysmart.CommonUtils.Logger;
import com.cypress.cysmart.CommonUtils.UUIDDatabase;
import com.cypress.cysmart.CommonUtils.Utils;
import com.cypress.cysmart.DataModelClasses.PairOnConnect;
import com.cypress.cysmart.HomePageActivity;
import com.cypress.cysmart.ListAdapters.CarouselPagerAdapter;
import com.cypress.cysmart.R;

import java.util.List;

public class ProfileControlFragment extends Fragment {

    public static final float BIG_SCALE = 1.0f;
    public static final float SMALL_SCALE = 0.7f;
    public static final float DIFF_SCALE = BIG_SCALE - SMALL_SCALE;
    public static final int PAIR_DELAY_MILLIS = 500;
    public static final int PAIRING_NO_BONDING_PROGRESS_DIALOG_TIME_OUT_MILLIS = 6000; // less than 6 seconds doesn't work for Nexus 5
    public static int LOOPS = 100;
    // CarouselView related variables
    public static int mPages = 0;
    public static int FIRST_PAGE = mPages * LOOPS / 2;
    // ViewPager for CarouselView
    public ViewPager mPager;
    // Adapter for loading data to CarouselView
    private CarouselPagerAdapter mAdapter;
    private int mWidth = 0;
    public static boolean mIsInFragment = false;
    private boolean mFirstTime = false;
    private boolean mPairOnConnectStatusReceiverRegistered = false;

    private BluetoothGattCharacteristic mServiceChangedCharacteristic;
    private BluetoothGattDescriptor mServiceChangedCCCD;

    private final BroadcastReceiver mPairOnConnectStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {

                Bundle extras = intent.getExtras();
                if (extras.containsKey(Constants.EXTRA_DESCRIPTOR_BYTE_VALUE_UUID)
                        && extras.containsKey(Constants.EXTRA_DESCRIPTOR_BYTE_VALUE_CHARACTERISTIC_UUID)) {

                    String descriptorUUID = extras.getString(Constants.EXTRA_DESCRIPTOR_BYTE_VALUE_UUID);
                    String characteristicUUID = extras.getString(Constants.EXTRA_DESCRIPTOR_BYTE_VALUE_CHARACTERISTIC_UUID);
                    if (GattAttributes.CLIENT_CHARACTERISTIC_CONFIG.equalsIgnoreCase(descriptorUUID)
                            && GattAttributes.SERVICE_CHANGED.equalsIgnoreCase(characteristicUUID)) {

                        Logger.d("PCF: pair: onDescriptorRead(CCCD): SUCCESS");
                        if (mServiceChangedCharacteristic != null) {
                            BluetoothLeService.setCharacteristicIndication(mServiceChangedCharacteristic, true);
                        }
                    }
                }
            } else if (BluetoothLeService.ACTION_GATT_INSUFFICIENT_ENCRYPTION.equals(action)) { // this event is not being thrown for Samsung S7

                Logger.d("PCF: pair: onDescriptorWrite(CCCD): BluetoothLeService.ACTION_GATT_INSUFFICIENT_ENCRYPTION");
                // It is necessary to set characteristic indication for the 2nd time to kick off pairing
                if (mServiceChangedCharacteristic != null) {
                    BluetoothLeService.setCharacteristicIndication(mServiceChangedCharacteristic, true);
                    /*
                     * 1. For the case of pairing with bonding the ACTION_BOND_STATE_CHANGED event is being fired.
                     * As a result of ACTION_BOND_STATE_CHANGED event the HomePageActivity.mProgressDialog is being shown.
                     * Hence there is no sense to showToast ProfileControlFragment.mProgressDialog here.
                     *
                     * 2. For the case of pairing without bonding the ACTION_BOND_STATE_CHANGED event is not being fired.
                     * As a result the HomePageActivity.mProgressDialog is not being shown.
                     * Hence it is necessary to showToast ProfileControlFragment.mProgressDialog here.
                     *
                     * To satisfy both cases we are using HomePageActivity.mProgressDialog instead of ProfileControlFragment.mProgressDialog here.
                     */
                    HomePageActivity activity = (HomePageActivity) getActivity();
                    Utils.showBondingProgressDialog(activity, activity.mProgressDialog, PAIRING_NO_BONDING_PROGRESS_DIALOG_TIME_OUT_MILLIS);
                }
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.d("PCF: lifecycle: onCreate " + this + ", " + getActivity());

        //Hiding the softkeyboard if visible
        View view = getActivity().getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

        // Getting the width of the device
        Display display = getActivity().getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        mWidth = size.x;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Logger.d("PCF: lifecycle: onCreateView " + this + ", " + getActivity());
        View rootView = inflater.inflate(R.layout.profile_control, container, false);
        mPager = rootView.findViewById(R.id.myviewpager);
        mPages = 0;
        setCarouselView();
        setHasOptionsMenu(true);

        /**
         * Getting the orientation of the device. Set margin for pages as a
         * negative number, so a part of next and previous pages will be showed
         */
        if (getActivity().getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            mPager.setPageMargin(-mWidth / 3);
        } else if (getActivity().getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mPager.setPageMargin(-mWidth / 2);
        }

        mFirstTime = true;

        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();
        Logger.d("PCF: lifecycle: onStart " + this + ", " + getActivity());
    }

    @Override
    public void onResume() {
        super.onResume();
        Logger.d("PCF: lifecycle: onResume " + this + ", " + getActivity());

        mIsInFragment = true;
        // Initialize ActionBar as per requirement
        Utils.setUpActionBar(getActivity(), R.string.profile_control_fragment);

        if (BluetoothLeService.getConnectionState() == BluetoothLeService.STATE_DISCONNECTED) {
            // Get the user back to the profile scanning fragment
            Intent intent = getActivity().getIntent();
            getActivity().finish();
            getActivity().overridePendingTransition(R.anim.slide_left, R.anim.push_left);
            startActivity(intent);
            getActivity().overridePendingTransition(R.anim.slide_right, R.anim.push_right);
        } else {
            Logger.d("PCF: pair: registering mPairOnConnectStatusReceiver");
            BluetoothLeService.registerBroadcastReceiver(getActivity(), mPairOnConnectStatusReceiver, Utils.makeGattUpdateIntentFilter());
            mPairOnConnectStatusReceiverRegistered = true;

            if (mFirstTime) {
                mFirstTime = false;
                if (PairOnConnect.isPairOnConnect(getActivity())) {
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            initiatePairingIfSupported();
                        }
                    }, PAIR_DELAY_MILLIS);
                }
            }
        }
    }

    @Override
    public void onPause() {
        Logger.d("PCF: lifecycle: onPause " + this + ", " + getActivity());
        mIsInFragment = false;
        if (mPairOnConnectStatusReceiverRegistered) {
            Logger.d("PCF: pair: unregistering mPairOnConnectStatusReceiver");
            BluetoothLeService.unregisterBroadcastReceiver(getActivity(), mPairOnConnectStatusReceiver);
            mPairOnConnectStatusReceiverRegistered = false;
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        Logger.d("PCF: lifecycle: onStop " + this + ", " + getActivity());
        super.onStop();
    }

    @Override
    public void onDestroy() {
        Logger.d("PCF: lifecycle: onDestroy " + this + ", " + getActivity());

        // Dismiss the dialog if it is shown and we are leaving the fragment
        HomePageActivity activity = (HomePageActivity) getActivity();
        Utils.hideBondingProgressDialog(activity.mProgressDialog);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Getting the width on orientation changed
        Display display = getActivity().getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int width = size.x;

        /**
         * Getting the orientation of the device. Set margin for pages as a
         * negative number, so a part of next and previous pages will be showed
         */
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mPager.setPageMargin(-width / 2);
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            mPager.setPageMargin(-width / 3);
        }
        mPager.refreshDrawableState();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.global, menu);
        MenuItem graph = menu.findItem(R.id.graph);
        MenuItem log = menu.findItem(R.id.log);
        MenuItem search = menu.findItem(R.id.search);
        MenuItem clearCache = menu.findItem(R.id.clearcache);

        if (false == search.isActionViewExpanded()) {
            search.collapseActionView();
            search.getActionView().clearFocus();
            Logger.e("Action view" + search.isActionViewExpanded());
            search.setActionView(null);
        }
        search.setVisible(false);
        graph.setVisible(false);
        log.setVisible(true);
        clearCache.setVisible(true);
        super.onCreateOptionsMenu(menu, inflater);
    }

    /**
     * Setting the CarouselView with data
     */
    private void setCarouselView() {
        // Getting the number of services discovered
        mPages = ServiceDiscoveryFragment.mGattServiceData.size();
        FIRST_PAGE = mPages * LOOPS / 2;

        // Setting the adapter
        mAdapter = new CarouselPagerAdapter(getActivity(), ProfileControlFragment.this, getActivity().getSupportFragmentManager(), ServiceDiscoveryFragment.mGattServiceData);
        mPager.setAdapter(mAdapter);
        mPager.setOnPageChangeListener(mAdapter);

        // Set current item to the middle page so we can fling to both
        // directions left and right
        mPager.setCurrentItem(FIRST_PAGE);

        // Necessary or the pager will only have one extra page to showToast
        // make this at least however many pages you can see
        mPager.setOffscreenPageLimit(3);

        if (mPages == 0) {
            Toast.makeText(getActivity(), getResources().getString(R.string.toast_no_services_found), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(getActivity(), getResources().getString(R.string.toast_swipe_profiles), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Enable indication on ServiceNameChanged characteristic to initiate pairing process
     */
    private void initiatePairingIfSupported() {
        List<BluetoothGattService> services = BluetoothLeService.getSupportedGattServices();
        if (services != null) {
            for (BluetoothGattService service : services) {
                if (UUIDDatabase.UUID_GENERIC_ATTRIBUTE_SERVICE.equals(service.getUuid())) {
                    mServiceChangedCharacteristic = service.getCharacteristic(UUIDDatabase.UUID_SERVICE_CHANGED);
                    if (mServiceChangedCharacteristic != null) {
                        mServiceChangedCCCD = mServiceChangedCharacteristic.getDescriptor(UUIDDatabase.UUID_CLIENT_CHARACTERISTIC_CONFIG);
                        if (mServiceChangedCCCD != null && (mServiceChangedCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            // If the peripheral uses pairing without bonding then simply enabling notification for the Service Changed characteristic works, i.e. pairing is being automatically started, but there is no
                            // notification from the Android that the pairing is in progress. To get some notification from Android that the pairing is in progress (to be able to showToast 'Pairing in progress' alert)
                            // the following trick is being used:
                            // 1. Read the Service Changed characteristic's CCCD - we should receive SUCCESS response.
                            // 2. Enable notification for the Service Changed characteristic - we get INSUFFICIENT_ENCRYPTION event, though the pairing is not automatically started.
                            // 3. Enable notification for the Service Changed characteristic once again - this time the pairing is automatically started.
                            BluetoothLeService.readDescriptor(mServiceChangedCCCD);
                        }
                    }
                    break;
                }
            }
        }
    }
}

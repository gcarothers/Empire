/*
 * Copyright (c) 2009-2011 Clark & Parsia, LLC. <http://www.clarkparsia.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.clarkparsia.empire.config;

import com.clarkparsia.empire.util.EmpireAnnotationProvider;
import com.clarkparsia.empire.util.PropertiesAnnotationProvider;
import com.clarkparsia.empire.EmpireOptions;

import com.clarkparsia.utils.BasicUtils;
import com.clarkparsia.utils.NamespaceUtils;

import java.util.Map;
import java.util.HashMap;

import java.lang.reflect.Field;

/**
 * <p>A simple container class for EmpireConfiguration information.</p>
 *
 * @author Michael Grove
 * @since 0.6.2
 * @version 0.7
 */
public class EmpireConfiguration {
	private Class<? extends EmpireAnnotationProvider> mAnnotationProvider = PropertiesAnnotationProvider.class;

	private Map<String, String> mGeneralConfiguration = new HashMap<String, String>();

	private Map<String, Map<String, String>> mUnitConfiguration = new HashMap<String, Map<String, String>>();

	public EmpireConfiguration() {
	}

	public EmpireConfiguration(final Map<String, String> theGeneralConfiguration,
							   final Map<String, Map<String, String>> theUnitConfiguration) {
		
		mGeneralConfiguration = theGeneralConfiguration;
		mUnitConfiguration = theUnitConfiguration;

		for (String aKey : mGeneralConfiguration.keySet()) {
			try {
				Field aField = EmpireOptions.class.getField(aKey);
				
				aField.setBoolean(null, Boolean.parseBoolean(mGeneralConfiguration.get(aKey)));
			}
			catch (Exception e) {
				// no-op, field doesn't exist, or the value is badly formatted. oh well
			}
		}

		// auto add any namespace declarations in the configuration file.
		if (mGeneralConfiguration.containsKey("ns_list")) {
			String aList = mGeneralConfiguration.get("ns_list");
			for (String aKey : BasicUtils.split(aList, ",")) {
				if (mGeneralConfiguration.containsKey(aKey)) {
					NamespaceUtils.addNamespace(aKey, mGeneralConfiguration.get(aKey));
				}
			}
		}
	}

	public Class<? extends EmpireAnnotationProvider> getAnnotationProvider() {
		return mAnnotationProvider;
	}

	public void setAnnotationProvider(final Class<? extends EmpireAnnotationProvider> theAnnotationProvider) {
		mAnnotationProvider = theAnnotationProvider;
	}

	public Map<String, String> getUnitConfig(final String theUnitName) {
		return mUnitConfiguration.get(theUnitName);
	}

	public Map<String, String> getGlobalConfig() {
		return mGeneralConfiguration;
	}

	public String get(String theKey) {
		return mGeneralConfiguration.get(theKey);
	}

	public boolean hasUnit(final String theUnitName) {
		return mUnitConfiguration.containsKey(theUnitName);
	}
}

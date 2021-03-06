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

package com.clarkparsia.empire.annotation;

import com.clarkparsia.utils.AbstractDataCommand;
import com.clarkparsia.utils.NamespaceUtils;

import org.openrdf.model.BNode;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Value;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.Statement;

import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.XMLSchema;
import org.openrdf.model.vocabulary.RDFS;

import java.lang.reflect.Type;

import java.util.Arrays;

import java.util.Date;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Modifier;

import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.Locale;
import java.util.ArrayList;
import java.net.URISyntaxException;

import com.clarkparsia.utils.BasicUtils;
import com.clarkparsia.utils.Function;
import com.clarkparsia.utils.Predicate;
import com.clarkparsia.utils.io.Encoder;

import com.clarkparsia.utils.collections.CollectionUtil;
import static com.clarkparsia.utils.collections.CollectionUtil.filter;
import static com.clarkparsia.utils.collections.CollectionUtil.find;

import org.openrdf.model.impl.ValueFactoryImpl;

import org.apache.log4j.Logger;
import org.apache.log4j.LogManager;

import com.clarkparsia.empire.ds.DataSource;
import com.clarkparsia.empire.ds.DataSourceException;
import com.clarkparsia.empire.ds.QueryException;
import com.clarkparsia.empire.ds.DataSourceUtil;
import com.clarkparsia.empire.EmpireOptions;
import com.clarkparsia.empire.SupportsRdfId;
import com.clarkparsia.empire.Empire;
import com.clarkparsia.empire.Dialect;
import com.clarkparsia.empire.annotation.runtime.Proxy;

import com.clarkparsia.empire.impl.serql.SerqlDialect;
import com.clarkparsia.empire.impl.sparql.ARQSPARQLDialect;

import static com.clarkparsia.empire.util.BeanReflectUtil.set;
import static com.clarkparsia.empire.util.BeanReflectUtil.setAccessible;
import static com.clarkparsia.empire.util.BeanReflectUtil.getAnnotatedFields;
import static com.clarkparsia.empire.util.BeanReflectUtil.getAnnotatedGetters;
import static com.clarkparsia.empire.util.BeanReflectUtil.getAnnotatedSetters;
import static com.clarkparsia.empire.util.BeanReflectUtil.get;

import com.clarkparsia.empire.util.BeanReflectUtil;
import com.clarkparsia.empire.util.EmpireUtil;
import static com.clarkparsia.empire.util.EmpireUtil.asPrimaryKey;
import com.clarkparsia.openrdf.util.ResourceBuilder;
import com.clarkparsia.openrdf.util.GraphBuilder;
import com.clarkparsia.openrdf.ExtGraph;

import com.google.inject.ProvisionException;
import com.google.inject.ConfigurationException;

import javax.persistence.Entity;
import javax.persistence.Transient;

import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyObject;
import javassist.util.proxy.MethodFilter;

/**
 * <p>Description: Utility for creating RDF from a compliant Java Bean, and for turning RDF (the results of a describe
 * on a given rdf:ID into a KB) into a Java bean.</p>
 * <p>Usage:<br/>
 * <code><pre>
 *   MyClass aObj = new MyClass();
 *
 *   // set some data on the object
 *   KB.add(RdfGenerator.toRdf(aObj));
 *
 *   MyClass aObjCopy = RdfGenerator.fromRdf(MyClass.class, aObj.getRdfId(), KB);
 *
 *   // this will print true
 *   System.out.println(aObj.equals(aObjCopy));
 * </pre>
 * </code>
 * </p>
 * <p>
 * Compliant classes must be annotated with the {@link Entity} JPA annotation, the {@link RdfsClass} annotation,
 * and must implement the {@link SupportsRdfId} interface.</p>
 *
 * @author Michael Grove
 * @since 0.1
 * @version 0.7
 */
public class RdfGenerator {

	/**
	 * Global ValueFactory to use for converting Java values into sesame objects for serialization to RDF
	 */
	private static final ValueFactory FACTORY = new ValueFactoryImpl();

	private static final ContainsResourceValues CONTAINS_RESOURCES = new ContainsResourceValues();

	private static final LanguageFilter LANG_FILTER = new LanguageFilter(getLanguageForLocale());

	/**
	 * The logger
	 */
	private static final Logger LOGGER = LogManager.getLogger(RdfGenerator.class.getName());

	/**
	 * Map from rdf:type URI's to the Java class which corresponds to that resource.
	 */
	private final static Map<URI, Class> TYPE_TO_CLASS = new HashMap<URI, Class>();

	/**
	 * Map to keep a record of what instances are currently being created in order to prevent cycles.  Keys are the
	 * identifiers of the instances and the values are the instances
	 */
	public final static Map<Object, Object> OBJECT_M = new HashMap<Object, Object>();

	private final static Set<Class<?>> REGISTERED_FOR_NS = new HashSet<Class<?>>();

	/**
	 * Initialize some parameters in the RdfGenerator.  This caches namespace and type mapping information locally
	 * which will be used in subsequent rdf generation requests.
	 * @param theClasses the list of classes to be handled by the RdfGenerator
	 */
	public static synchronized void init(Collection<Class<?>> theClasses) {
		for (Class<?> aClass : theClasses) {
			RdfsClass aAnnotation = aClass.getAnnotation(RdfsClass.class);

			if (aAnnotation != null) {
				addNamespaces(aClass);

				TYPE_TO_CLASS.put(FACTORY.createURI(NamespaceUtils.uri(aAnnotation.value())), aClass);
			}
		}
	}

	/**
	 * Create an instance of the specified class and instantiate it's data from the given data source using the RDF
	 * instance specified by the given URI
	 * @param theClass the class to create
	 * @param theKey the id of the RDF individual containing the data for the new instance
	 * @param theSource the KB to get the RDF data from
	 * @param <T> the type of the instance to create
	 * @return a new instance
	 * @throws InvalidRdfException thrown if the class does not support RDF JPA operations, or does not provide sufficient access to its fields/data.
	 * @throws DataSourceException thrown if there is an error while retrieving data from the graph
	 */
	public static <T> T fromRdf(Class<T> theClass, String theKey, DataSource theSource) throws InvalidRdfException, DataSourceException {
		return fromRdf(theClass, EmpireUtil.asPrimaryKey(theKey), theSource);
	}

	/**
	 * Create an instance of the specified class and instantiate it's data from the given data source using the RDF
	 * instance specified by the given URI
	 * @param theClass the class to create
	 * @param theURI the id of the RDF individual containing the data for the new instance
	 * @param theSource the KB to get the RDF data from
	 * @param <T> the type of the instance to create
	 * @return a new instance
	 * @throws InvalidRdfException thrown if the class does not support RDF JPA operations, or does not provide sufficient access to its fields/data.
	 * @throws DataSourceException thrown if there is an error while retrieving data from the graph
	 */
	public static <T> T fromRdf(Class<T> theClass, java.net.URI theURI, DataSource theSource) throws InvalidRdfException, DataSourceException {
		return fromRdf(theClass, new SupportsRdfId.URIKey(theURI), theSource);
	}

	/**
	 * Create an instance of the specified class and instantiate it's data from the given data source using the RDF
	 * instance specified by the given URI
	 * @param theClass the class to create
	 * @param theId the id of the RDF individual containing the data for the new instance
	 * @param theSource the KB to get the RDF data from
	 * @param <T> the type of the instance to create
	 * @return a new instance
	 * @throws InvalidRdfException thrown if the class does not support RDF JPA operations, or does not provide sufficient access to its fields/data.
	 * @throws DataSourceException thrown if there is an error while retrieving data from the graph
	 */
	public static <T> T fromRdf(Class<T> theClass, SupportsRdfId.RdfKey theId, DataSource theSource) throws InvalidRdfException, DataSourceException {
		T aObj;

		long start = System.currentTimeMillis();
		try {
			aObj = Empire.get().instance(theClass);
		}
		catch (ConfigurationException ex) {
			aObj = null;
		}
		catch (ProvisionException ex) {
			aObj = null;
		}
		LOGGER.debug("Tried to get instance of class : " + (System.currentTimeMillis()-start ) );
		start = System.currentTimeMillis();

		if (aObj == null) {
			// this means Guice construction failed, which is not surprising since that's not going to be the default.
			// so we'll try our own reflect based creation or create bytecode for an interface.

			try {
				long istart = System.currentTimeMillis();
				if (theClass.isInterface() || Modifier.isAbstract(theClass.getModifiers())) {		
					aObj = com.clarkparsia.empire.codegen.InstanceGenerator.generateInstanceClass(theClass).newInstance();
					LOGGER.debug("CodeGenerated instance in : " + (System.currentTimeMillis() - istart) + "ms. ");
				}
				else {
					aObj = theClass.newInstance();
					LOGGER.debug("CodeGenerated instance in : " + (System.currentTimeMillis() - istart) + "ms. ");
				}
			}
			catch (InstantiationException e) {
				throw new InvalidRdfException("Cannot create instance of bean, should have a default constructor.", e);
			}
			catch (IllegalAccessException e) {
				throw new InvalidRdfException("Could not access default constructor for class: " + theClass, e);
			}
			catch (Exception e) {
				throw new InvalidRdfException("Cannot create an instance of bean", e);
			}
			LOGGER.debug("Got reflect instance of class : " + (System.currentTimeMillis()-start ) );
			start = System.currentTimeMillis();

		}

		asSupportsRdfId(aObj).setRdfId(theId);
		LOGGER.debug("Has rdfId : " + (System.currentTimeMillis()-start ) );
		start = System.currentTimeMillis();

		return fromRdf(aObj, theSource);
	}

	/**
	 * Populate the fields of the current instance from the RDF indiviual with the given URI
	 * @param theObj the Java object to populate
	 * @param theSource the KB to get the RDF data from
	 * @param <T> the type of the class being populated
	 * @return theObj, populated from the specified DataSource
	 * @throws InvalidRdfException thrown if the object does not support the RDF JPA API.
	 * @throws DataSourceException thrown if there is an error retrieving data from the database
	 */
	@SuppressWarnings("unchecked")
	private synchronized static <T> T fromRdf(T theObj, DataSource theSource) throws InvalidRdfException, DataSourceException {
		final SupportsRdfId aTmpSupportsRdfId = asSupportsRdfId(theObj);
		final SupportsRdfId.RdfKey theKeyObj = aTmpSupportsRdfId.getRdfId();
		
		LOGGER.debug("Got obj : " + theObj );
		
		if (OBJECT_M.containsKey(theKeyObj)) {
			// TODO: this is probably a safe cast, i dont see how something w/ the same URI, which should be the same
			// object would change types
			return (T) OBJECT_M.get(theKeyObj);
		}

		try {

			OBJECT_M.put(theKeyObj, theObj);

			ExtGraph aGraph = new ExtGraph(DataSourceUtil.describe(theSource, theObj));

			if (aGraph.size() == 0) {
				return theObj;
			}

			final Resource aTmpRes = EmpireUtil.asResource(aTmpSupportsRdfId);
			Set<URI> aProps = new HashSet<URI>();

			Iterator<Statement> sIter = aGraph.match(aTmpRes, null, null);

			while (sIter.hasNext()) {
				aProps.add(sIter.next().getPredicate());
			}
			
			for (URI aProp : aProps) {
				if (RDF.TYPE.equals(aProp)) {
					
					URI aType = (URI) aGraph.getValue(aTmpRes, aProp);
					if((TYPE_TO_CLASS.containsKey(aType)) 
						&& (!theObj.getClass().equals(TYPE_TO_CLASS.get(aType))) 
						&& (!BeanReflectUtil.sameRdfsClass(TYPE_TO_CLASS.get(aType), theObj.getClass()))) {
						try {
							Class aClass = TYPE_TO_CLASS.get(aType);
							
							if (aClass.isInterface() || Modifier.isAbstract(aClass.getModifiers())) {		
								theObj = (T) com.clarkparsia.empire.codegen.InstanceGenerator.generateInstanceClass(aClass).newInstance();
							}
							else {
								theObj = (T) aClass.newInstance();
							}
							
							asSupportsRdfId(theObj).setRdfId(theKeyObj);
						}
						catch (Exception e) {
							/* Forget it */
						}

					}
				}
			}
			
			final SupportsRdfId aSupportsRdfId = asSupportsRdfId(theObj);
			
			OBJECT_M.put(theKeyObj, theObj);
			final Resource aRes = EmpireUtil.asResource(aSupportsRdfId);
			
			Collection<Field> aFields = getAnnotatedFields(theObj.getClass());
			Collection<Method> aMethods = getAnnotatedSetters(theObj.getClass(), true);

			addNamespaces(theObj.getClass());

			final Map<URI, AccessibleObject> aAccessMap = new HashMap<URI, AccessibleObject>();

			CollectionUtil.each(aFields, new AbstractDataCommand<Field>() {
				public void execute() {

					if (getData().getAnnotation(RdfProperty.class) != null) {
						aAccessMap.put(FACTORY.createURI(NamespaceUtils.uri(getData().getAnnotation(RdfProperty.class).value())),
									   getData());
					}
					else {
						String aBase = "urn:empire:clark-parsia:";
						if (aRes instanceof URI) {
							aBase = ((URI)aRes).getNamespace();
						}

						aAccessMap.put(FACTORY.createURI(aBase + getData().getName()),
									   getData());
					}
				}
			});

			CollectionUtil.each(aMethods, new AbstractDataCommand<Method>() {
				public void execute() {
					RdfProperty aAnnotation = BeanReflectUtil.getAnnotation(getData(), RdfProperty.class);
					if (aAnnotation != null) {
						aAccessMap.put(FACTORY.createURI(NamespaceUtils.uri(aAnnotation.value())),
									   getData());
					}
				}
			});			

			for (URI aProp : aProps) {
				AccessibleObject aAccess = aAccessMap.get(aProp);

				if (aAccess == null && RDF.TYPE.equals(aProp)) {
					// we can skip the rdf:type property.  it's basically assigned in the @RdfsClass annotation on the
					// java class, so we can figure it out later if need be. TODO: of course, if something has multiple types
					// that information is lost, which is not good.

					URI aType = (URI) aGraph.getValue(aRes, aProp);
					if (!TYPE_TO_CLASS.containsKey(aType) ||
						!TYPE_TO_CLASS.get(aType).isAssignableFrom(theObj.getClass())) {

						if (TYPE_TO_CLASS.containsKey(aType) && !TYPE_TO_CLASS.get(aType).getName().equals(theObj.getClass().getName())) {
							// TODO: this might just be an error
							LOGGER.warn("Asserted rdf:type of the individual does not match the rdf:type annotation on the object. " + aType + " " + TYPE_TO_CLASS.get(aType) + " " + theObj.getClass() + " " +TYPE_TO_CLASS.get(aType).isAssignableFrom(theObj.getClass())+ " " +TYPE_TO_CLASS.get(aType).equals(theObj.getClass()) + " " + TYPE_TO_CLASS.get(aType).getName().equals(theObj.getClass().getName()));
						}
						else {
							// if they're not equals() or isAssignableFrom, but have the same name, this is usually
							// means that the class loaders don't match.  so probably not an error, so no warning.
						}
					}

					continue;
				}
				else if (aAccess == null) {
					// TODO: this is a lossy transformation, there's rdf data which is not represented by a field on the java class
					// so if we don't convert it into something on the java bean, they don't have a full representation of
					// what was in the database AND if they save that back to the database, they will lose this information
					// that is not good either.
					continue;
				}

				ToObjectFunction aFunc = new ToObjectFunction(theSource, aRes, aAccess, aProp);

				Object aValue = aFunc.apply(aGraph.getValues(aRes, aProp));

				boolean aOldAccess = aAccess.isAccessible();

				try {
					setAccessible(aAccess, true);					
					set(aAccess, theObj, aValue);				
				}				
				catch (InvocationTargetException e) {
					// oh crap
					throw new InvalidRdfException(e);
				}
				catch (IllegalAccessException e) {
					// this should not happen since we toggle the accessibility of the field, but we'll re-throw regardless
					throw new InvalidRdfException(e);
				}
				catch (IllegalArgumentException e) {
					// this is "likely" to happen.  we'll get this exception if the rdf does not match the java.  for example
					// if something is specified to be an int in the java class, but it typed as a float (though down conversion
					// in that case might work) the set call will fail.
					// TODO: shouldnt this be an error?
					LOGGER.warn("Probable type mismatch: " + aValue + " " + aAccess);
				}
				catch (RuntimeException e) {
					// TODO: i dont like keying on a RuntimeException here to get the error condition, but since the
					// Function interface does not throw anything, this is the best we can do.  maybe consider a
					// version of the Function interface that has a throws clause, it would make this more clear.

					// this was probably an error converting from a Value to an Object
					throw new InvalidRdfException(e);
				}
				finally {
					setAccessible(aAccess, aOldAccess);
				}
			}

			return theObj;
		}
		finally {
			OBJECT_M.remove(theKeyObj);
		}
	}


	/**
	 * Return the RdfClass annotation on the object.
	 * @param theObj the object to get that annotation from
	 * @return the objects' RdfClass annotation
	 * @throws InvalidRdfException thrown if the object does not have the required annotation, does not have an @Entity
	 * annotation, or does not {@link SupportsRdfId support Rdf Id's}
	 */
	private static RdfsClass asValidRdfClass(Object theObj) throws InvalidRdfException {
		if (!BeanReflectUtil.hasAnnotation(theObj.getClass(), RdfsClass.class)) {
			throw new InvalidRdfException("Specified value is not an RdfsClass object");
		}

		if (EmpireOptions.ENFORCE_ENTITY_ANNOTATION && !BeanReflectUtil.hasAnnotation(theObj.getClass(), Entity.class)) {
			throw new InvalidRdfException("Specified value is not a JPA Entity object");
		}

		// verify that it supports rdf id's
		asSupportsRdfId(theObj);

		return BeanReflectUtil.getAnnotation(theObj.getClass(), RdfsClass.class);
	}

	/**
	 * Return the object casted to {@link SupportsRdfId}
	 * @param theObj the object to cast
	 * @return the object, casted to the interface
	 * @throws InvalidRdfException thrown if the object does not implement the interface
	 */
	private static SupportsRdfId asSupportsRdfId(Object theObj) throws InvalidRdfException {
		if (!(theObj instanceof SupportsRdfId)) {
			throw new InvalidRdfException("Object of type '" + (theObj.getClass().getName()) + "' does not implements SupportsRdfId, anonymous instances are not supported.");
		}
		else {
			return (SupportsRdfId) theObj;
		}
	}

	/**
	 * Given an object, return it's rdf:ID.  If it already has an id, that will be returned, otherwise the id
	 * will either be generated from the data, using the {@link RdfId} annotation as a guide, or it will auto-generate one.
	 * @param theObj the object
	 * @return the object's rdf:Id
	 * @throws InvalidRdfException thrown if the object does not support the minimum to create or retrieve an rdf:ID
	 * @see SupportsRdfId
	 */
	public static Resource id(Object theObj) throws InvalidRdfException {
		SupportsRdfId aSupport = asSupportsRdfId(theObj);

		if (aSupport.getRdfId() != null) {
			return EmpireUtil.asResource(aSupport);
		}

		Field aIdField = BeanReflectUtil.getIdField(theObj.getClass());

		String aValue = hash(BasicUtils.getRandomString(10));
		String aNS = RdfId.DEFAULT;

		URI aURI = FACTORY.createURI(aNS + aValue);

		if (aIdField != null && !aIdField.getAnnotation(RdfId.class).namespace().equals("")) {
			aNS = aIdField.getAnnotation(RdfId.class).namespace();
		}

		if (aIdField != null) {
			boolean aOldAccess = aIdField.isAccessible();
			aIdField.setAccessible(true);

			try {
				if (aIdField.get(theObj) == null) {
					throw new InvalidRdfException("id field must have a value");
				}

				Object aValObj = aIdField.get(theObj);

				aValue = Encoder.urlEncode(aValObj.toString());

				if (aValObj instanceof java.net.URI || BasicUtils.isURI(aValObj.toString())) {
					try {
						aURI = FACTORY.createURI(aValObj.toString());
					}
					catch (IllegalArgumentException e) {
						// sometimes sesame disagrees w/ Java about what a valid URI is.  so we'll have to try
						// and construct a URI from the possible fragment
						aURI = FACTORY.createURI(aNS + aValue);
					}
				}
				else {
					//aValue = hash(aValObj);
					aURI = FACTORY.createURI(aNS + aValue);
				}
			}
			catch (IllegalAccessException ex) {
				throw new InvalidRdfException(ex);
			}

			aIdField.setAccessible(aOldAccess);
		}

		aSupport.setRdfId(new SupportsRdfId.URIKey(java.net.URI.create(aURI.toString())));

		return aURI;
	}

	/**
	 * Scan the object for {@link Namespaces} annotations and add them to the current list of known namespaces
	 * @param theObj the object to scan.
	 */
	public static void addNamespaces(Class<?> theObj) {
		if (theObj == null || REGISTERED_FOR_NS.contains(theObj)) {
			return;
		}

		REGISTERED_FOR_NS.add(theObj);

		Namespaces aNS = BeanReflectUtil.getAnnotation(theObj, Namespaces.class);

		if (aNS == null) {
			return;
		}

		int aIndex = 0;
		while (aIndex+1 < aNS.value().length) {
			String aPrefix = aNS.value()[aIndex];
			String aURI = aNS.value()[aIndex+1];

			// TODO: maybe have a local version of this, this will add a global namespace, and could potentially
			// overwrite global things that use the same prefix but different uris, which would be bad
			NamespaceUtils.addNamespace(aPrefix, aURI);
			aIndex += 2;
		}
	}

	/**
	 * Return the given Java bean as a set of RDF triples
	 * @param theObj the object
	 * @return the object represented as RDF triples
	 * @throws InvalidRdfException thrown if the object cannot be transformed into RDF.
	 */
	public static ExtGraph asRdf(Object theObj) throws InvalidRdfException {
		if (theObj == null) {
			return null;
		}

		RdfsClass aClass = asValidRdfClass(theObj);

		Resource aSubj = id(theObj);

		addNamespaces(theObj.getClass());

		GraphBuilder aBuilder = new GraphBuilder();

		Collection<AccessibleObject> aAccessors = new HashSet<AccessibleObject>();
		aAccessors.addAll(getAnnotatedFields(theObj.getClass()));
		aAccessors.addAll(getAnnotatedGetters(theObj.getClass(), true));

		try {
			ResourceBuilder aRes = aBuilder.instance(aBuilder.getValueFactory().createURI(NamespaceUtils.uri(aClass.value())),
													 aSubj);

			for (AccessibleObject aAccess : aAccessors) {
				LOGGER.debug("Getting rdf for : " + aAccess.toString() );
				AsValueFunction aFunc = new AsValueFunction(aAccess);

				if (aAccess.isAnnotationPresent(Transient.class)
					|| (aAccess instanceof Field
						&& Modifier.isTransient( ((Field)aAccess).getModifiers() ))) {

					// transient fields or accessors with the Transient annotation do not get converted.
					continue;
				}

				RdfProperty aPropertyAnnotation = BeanReflectUtil.getAnnotation(aAccess, RdfProperty.class);
				String aBase = "urn:empire:clark-parsia:";
				if (aRes instanceof URI) {
					aBase = ((URI)aRes).getNamespace();
				}

				URI aProperty = aPropertyAnnotation != null
								? aBuilder.getValueFactory().createURI(NamespaceUtils.uri(aPropertyAnnotation.value()))
								: (aAccess instanceof Field ? aBuilder.getValueFactory().createURI(aBase + ((Field)aAccess).getName()) : null);

				boolean aOldAccess = aAccess.isAccessible();
				setAccessible(aAccess, true);

				Object aValue = get(aAccess, theObj);

				setAccessible(aAccess, aOldAccess);

				if (aValue == null || aValue.toString().equals("")) {
					continue;
				}
				else if (Collection.class.isAssignableFrom(aValue.getClass())) {
					@SuppressWarnings("unchecked")
					List<Value> aValueList = asList(aAccess, (Collection<Object>) Collection.class.cast(aValue));

					if (aValueList.isEmpty()) {
						continue;
					}

					if (aPropertyAnnotation.isList()) {
						aRes.addProperty(aProperty, aValueList);
					}
					else {
						for (Value aVal : aValueList) {
							aRes.addProperty(aProperty, aVal);
						}
					}
				}
				else {
					aRes.addProperty(aProperty, aFunc.apply(aValue));
				}
			}
		}
		catch (IllegalAccessException e) {
			throw new InvalidRdfException(e);
		}
		catch (RuntimeException e) {
			throw new InvalidRdfException(e);
		}
		catch (InvocationTargetException e) {
			throw new InvalidRdfException("Cannot invoke method", e);
		}

		return aBuilder.graph();
	}

	/**
	 * Transform a list of Java Objects into the corresponding RDF values
	 * @param theAccess the accessor for the value
	 * @param theCollection the collection to transform
	 * @return the collection as a list of RDF values
	 * @throws InvalidRdfException thrown if any of the values cannot be transformed
	 */
	private static List<Value> asList(AccessibleObject theAccess, Collection<Object> theCollection) throws InvalidRdfException {
		try {
			return CollectionUtil.list(CollectionUtil.transform(theCollection, new AsValueFunction(theAccess)));
		}
		catch (RuntimeException e) {
			e.printStackTrace();
			throw new InvalidRdfException(e.getMessage());
		}
	}

	/**
	 * Return a base64 encoded md5 hash of the given object
	 * @param theObj the object to hash
	 * @return the hashed version of the object.
	 */
	private static String hash(Object theObj) {
		return BasicUtils.hex(BasicUtils.md5(theObj.toString()));
	}

	/**
	 * Javassist {@link MethodHandler} implementation for method proxying.
	 * @param <T> the proxy class type
	 */
	private static class CollectionProxyHandler implements MethodHandler {

		/**
		 * The proxy object which wraps the instance being proxied.
		 */
		private CollectionProxy mProxy;

		/**
		 * Create a new ProxyHandler
		 * @param theProxy the proxy object
		 */
		private CollectionProxyHandler(final CollectionProxy theProxy) {
			mProxy = theProxy;
		}

		/**
		 * Delegates the methods to the Proxy
		 * @inheritDoc
		 */
		public Object invoke(final Object theThis, final Method theMethod, final Method theProxyMethod, final Object[] theArgs) throws Throwable {
			return theMethod.invoke(mProxy.value(), theArgs);
		}
	}

	private static class CollectionProxy {
		private Collection mCollection;
		private AccessibleObject mField;
		private Collection<Value> theList;
		private ValueToObject valueToObject;

		public CollectionProxy(final AccessibleObject theField, final Collection<Value> theTheList, final ValueToObject theValueToObject) {
			mField = theField;
			theList = theTheList;
			valueToObject = theValueToObject;
		}

		private void init() {
			Collection<Object> aValues = BeanReflectUtil.instantiateCollectionFromField(BeanReflectUtil.classFrom(mField));

			for (Value aValue : theList) {
				Object aListValue = valueToObject.apply(aValue);

				if (aListValue == null) {
					throw new RuntimeException("Error converting a list value.");
				}

				aValues.add(aListValue);
			}

			mCollection = aValues;
		}

		public Collection value() {
			if (mCollection == null) {
				init();
				theList.clear();

				theList = null;
				mField = null;
				valueToObject = null;
			}

			return mCollection;
		}
	}

	/**
	 * Enabling this seems to use more memory than per-object proxying (or none at all).  Is javassist leaking memory?
	 * Experimental option, not currently used.
	 */
	@Deprecated
	public static final boolean PROXY_COLLECTIONS = false;

	/**
	 * Implementation of the function interface to turn a Collection of RDF values into Java bean(s).
	 */
	private static class ToObjectFunction implements Function<Collection<Value>, Object> {
		/**
		 * Function to turn a single value into an object
		 */
		private ValueToObject valueToObject;

		/**
		 * Reference to the Type which the values will be assigned
		 */
		private AccessibleObject mField;

		public ToObjectFunction(final DataSource theSource, Resource theResource, final AccessibleObject theField, final URI theProp) {
			valueToObject = new ValueToObject(theSource, theResource, theField, theProp);

			mField = theField;
		}

		public Object apply(final Collection<Value> theList) {
			if (theList == null || theList.isEmpty()) {
				return BeanReflectUtil.instantiateCollectionFromField(BeanReflectUtil.classFrom(mField));
			}
			if (Collection.class.isAssignableFrom(BeanReflectUtil.classFrom(mField))) {
				try {

					if (PROXY_COLLECTIONS && !BeanReflectUtil.isPrimitive(refineClass(mField, BeanReflectUtil.classFrom(mField), null, null))) {
						Object aColType = BeanReflectUtil.instantiateCollectionFromField(BeanReflectUtil.classFrom(mField));

						ProxyFactory aFactory = new ProxyFactory();
						aFactory.setInterfaces(aColType.getClass().getInterfaces());
						aFactory.setSuperclass(aColType.getClass());
						aFactory.setFilter(METHOD_FILTER);

						Object aResult = aFactory.createClass().newInstance();
						((ProxyObject) aResult).setHandler(new CollectionProxyHandler(new CollectionProxy(mField, theList, valueToObject)));
						return aResult;
					}
					else {
						Collection<Object> aValues = BeanReflectUtil.instantiateCollectionFromField(BeanReflectUtil.classFrom(mField));

						for (Value aValue : theList) {
							Object aListValue = valueToObject.apply(aValue);

							if (aListValue == null) {
								throw new RuntimeException("Error converting a list value.");
							}

							aValues.add(aListValue);
						}

						return aValues;
					}
				}
				catch (Exception e) {
					throw new RuntimeException(e);
				}
			}

			/**
			 * if not list all literals
			 *   proceed
			 * else
			 *  if not lang aware
			 * 	 find non lang typed literals
			 *   if >= 1 non lang typed literals
			 *     proceed
			 *   else get language based on locale
			 *     find literals based on local lang
			 *
			 *   if == 0 literals
			 *     use original list
			 *   else use filtered list
			 *
			 * else if lang aware
			 */

			Collection<Value> aList = new HashSet<Value>(theList);

			if (!find(aList, CONTAINS_RESOURCES)) {
				if (!EmpireOptions.ENABLE_LANG_AWARE) {
					Collection<Value> aLangFiltered = filter(aList, new Predicate<Value>() { public boolean accept(final Value theValue) { return ((Literal)theValue).getLanguage() == null; }});

					if (aLangFiltered.isEmpty()) {
						LANG_FILTER.setLangCode(getLanguageForLocale());
						aLangFiltered = filter(aList, LANG_FILTER);
					}

					if (!aLangFiltered.isEmpty()) {
						aList = aLangFiltered;
					}
				}
				else {
					LANG_FILTER.setLangCode(mField.getAnnotation(RdfProperty.class).language());
					aList = filter(aList, LANG_FILTER);
				}
			}

//			aList = filter(aList, new Predicate<Value>() {
//				public boolean accept(final Value theValue) {
//					if (theValue instanceof Resource
//						|| !EmpireOptions.ENABLE_LANG_AWARE
//						|| (EmpireOptions.ENABLE_LANG_AWARE
//							&& theValue instanceof Literal
//							&& !mField.getAnnotation(RdfProperty.class).language().equals(""))
//							&& mField.getAnnotation(RdfProperty.class).language().equals(((Literal)theValue).getLanguage())) {
//						return true;
//					}
//					else {
//						return false;
//					}
//				}
//			});


			if (aList.isEmpty()) {
				// yes, we checked for emptiness to begin the method, but we might have done some filtering based on the
				// language tags, so we need to check again.
				return BeanReflectUtil.instantiateCollectionFromField(BeanReflectUtil.classFrom(mField));
			}
			else if (aList.size() == 1) {
				// collection of one element, just convert the single element and send that back
				return valueToObject.apply(aList.iterator().next());
			}
			else {
				throw new RuntimeException("Cannot convert list of values to anything meaningful for the field. " + mField + " " + aList);
			}
		}
	}

	private static String getLanguageForLocale() {
		return Locale.getDefault() == null || Locale.getDefault().toString().equals("")
			   ? "en"
			   : (Locale.getDefault().toString().indexOf("_") != -1
				  ? Locale.getDefault().toString().substring(0, Locale.getDefault().toString().indexOf("_"))
				  : Locale.getDefault().toString());
	}

	private static Class refineClass(Object theAccessor, Class theClass, DataSource theSource, Resource theId) {
		Class aClass = theClass;

		if (Collection.class.isAssignableFrom(aClass)) {
			// if the field we're assigning from is a collection, try and figure out the type of the thing
			// we're creating from the collection

			Type[] aTypes = null;

			if (theAccessor instanceof Field && ((Field)theAccessor).getGenericType() instanceof ParameterizedType) {
				aTypes = ((ParameterizedType) ((Field)theAccessor).getGenericType()).getActualTypeArguments();
			}
			else if (theAccessor instanceof Method) {
				aTypes = ((Method) theAccessor).getGenericParameterTypes();
			}

			if (aTypes != null && aTypes.length >= 1) {
				// first type argument to a collection is usually the one we care most about
				if (aTypes[0] instanceof ParameterizedType && ((ParameterizedType)aTypes[0]).getActualTypeArguments().length > 0) {
					aClass = (Class) ((ParameterizedType)aTypes[0]).getActualTypeArguments()[0];
				}
				else if (aTypes[0] instanceof Class) {
					aClass = (Class) aTypes[0];
				}
			}
			else {
				// could not figure out the type from the generics assertions on the Collection, they are either
				// not present, or my algorithm is not bullet proof.  So lets try checking on the annotations
				// for a type hint.

				Class aTarget = BeanReflectUtil.getTargetEntity(theAccessor);
				if (aTarget != null) {
					aClass = aTarget;
				}
			}
		}

		if (!BeanReflectUtil.hasAnnotation(aClass, RdfsClass.class)) {
			// k, so either the parameter of the collection or the declared type of the field does
			// not map to an instance/bean type.  this is most likely an error, but lets try and find
			// the rdf:type of the field, and see if we can map that to a class in the path and we'll
			// create an instance of that.  that will work, and pushes the likely failure back off to
			// the assignment of the created instance

			URI aType = DataSourceUtil.getType(theSource, theId);

			// k, so now we know the type, if we can match the type to a class then we're in business
			if (aType != null) {
				Class aTypeClass = TYPE_TO_CLASS.get(aType);
				if (aTypeClass != null && BeanReflectUtil.hasAnnotation(aTypeClass, RdfsClass.class)) {
					// lets try this one
					aClass = aTypeClass;
				}
			}
		}

		return aClass;
	}

	public static class ValueToObject implements Function<Value, Object> {
		static final List<URI> integerTypes = Arrays.asList(XMLSchema.INT, XMLSchema.INTEGER, XMLSchema.POSITIVE_INTEGER,
													  XMLSchema.NEGATIVE_INTEGER, XMLSchema.NON_NEGATIVE_INTEGER,
													  XMLSchema.NON_POSITIVE_INTEGER, XMLSchema.UNSIGNED_INT);
		static final List<URI> longTypes = Arrays.asList(XMLSchema.LONG, XMLSchema.UNSIGNED_LONG);
		static final List<URI> floatTypes = Arrays.asList(XMLSchema.FLOAT, XMLSchema.DECIMAL);
		static final List<URI> shortTypes = Arrays.asList(XMLSchema.SHORT, XMLSchema.UNSIGNED_SHORT);
		static final List<URI> byteTypes = Arrays.asList(XMLSchema.BYTE, XMLSchema.UNSIGNED_BYTE);

		private URI mProperty;
		private Object mAccessor;
		private DataSource mSource;
		private Resource mResource;

		public ValueToObject(final DataSource theSource, Resource theResource, final Object theAccessor, final URI theProp) {
			mResource = theResource;
			mSource = theSource;
			mAccessor = theAccessor;
			mProperty = theProp;
		}

		public Object apply(final Value theValue) {
			if (mAccessor == null) {
				throw new RuntimeException("Null accessor is not permitted");
			}

			if (theValue instanceof Literal) {
				Literal aLit = (Literal) theValue;
				URI aDatatype = aLit.getDatatype() != null ? aLit.getDatatype() : null;
				if (aDatatype == null || XMLSchema.STRING.equals(aDatatype) || RDFS.LITERAL.equals(aDatatype)) {
					return aLit.getLabel();
				}
				else if (XMLSchema.BOOLEAN.equals(aDatatype)) {
					return Boolean.valueOf(aLit.getLabel());
				}
				else if (integerTypes.contains(aDatatype)) {
					return Integer.parseInt(aLit.getLabel());
				}
				else if (longTypes.contains(aDatatype)) {
					return Long.parseLong(aLit.getLabel());
				}
				else if (XMLSchema.DOUBLE.equals(aDatatype)) {
					return Double.valueOf(aLit.getLabel());
				}
				else if (floatTypes.contains(aDatatype)) {
					return Float.valueOf(aLit.getLabel());
				}
				else if (shortTypes.contains(aDatatype)) {
					return Short.valueOf(aLit.getLabel());
				}
				else if (byteTypes.contains(aDatatype)) {
					return Byte.valueOf(aLit.getLabel());
				}
				else if (XMLSchema.ANYURI.equals(aDatatype)) {
					try {
						return new java.net.URI(aLit.getLabel());
					}
					catch (URISyntaxException e) {
						LOGGER.warn("URI syntax exception converting literal value which is not a valid URI: " + aLit.getLabel());
						return null;
					}
				}
				else if (XMLSchema.DATE.equals(aDatatype) || XMLSchema.DATETIME.equals(aDatatype)) {
					return BasicUtils.asDate(aLit.getLabel());
				}
				else if (XMLSchema.TIME.equals(aDatatype)) {
					return new Date(Long.parseLong(aLit.getLabel()));
				}
				else {
					// no idea what this value is from its data type.  if the field takes a string
					// we'll just assign the plain string, otherwise its an error
					if (BeanReflectUtil.classFrom(mAccessor).isAssignableFrom(String.class)) {
						return aLit.getLabel();
					}
					else {
						throw new RuntimeException("Unsupported or unknown literal datatype");
					}
				}
			}
			else if (theValue instanceof BNode) {
				// TODO: this is not bulletproof, clean this up

				BNode aBNode = (BNode) theValue;

				// we need to figure out what type of bean this instance maps to.
				Class<?> aClass = BeanReflectUtil.classFrom(mAccessor);

				aClass = refineClass(mAccessor, aClass, mSource, aBNode);

				if (Collection.class.isAssignableFrom(BeanReflectUtil.classFrom(mAccessor))) {
					AccessibleObject aAccess = (AccessibleObject) mAccessor;
					RdfProperty aPropAnnotation = aAccess.getAnnotation(RdfProperty.class);

					// the field takes a collection, lets create a new instance of said collection, and hopefully the
					// bnode is a list.  this approach will only work if the property is a singleton value, eg
					// :inst someProperty _:a where _:a is the head of a list.  if you have another value _:b for
					// some property on :inst, we don't have any way of figuring out which one you're talking about
					// since bnode id references are not guaranteed to be stable in SPARQL, ie just because its id "a"
					// in the result set, does not mean i can do another query for _:a and get the expected results.
					// and you can't do a describe for the same reason.

					try {
						String aQuery = getBNodeConstructQuery(mSource, mResource, mProperty);
						
						ExtGraph aGraph = new ExtGraph(mSource.graphQuery(aQuery));
						Resource aPossibleListHead = (Resource) aGraph.getValue(mResource, mProperty);
						
						if (aGraph.isList(aPossibleListHead)) {
							List<Value> aList;

							// getting the list is only safe the the query dialect supports stable bnode ids in the query language, which is just 4store and Jena
							// sesame does not support this.  I know this is a shitty hack to detect this, but it works.  Future work will add this detection as an
							// interface to the dialect or as some sort of proeprty of the dialect, like dialect.supports(StableBNodeIds)
							if (aPropAnnotation != null && aPropAnnotation.isList() && mSource.getQueryFactory().getDialect() instanceof ARQSPARQLDialect) {
								try {
									aList = asList(mSource, aPossibleListHead);
								}
								catch (DataSourceException e) {
									throw new RuntimeException(e);
								}
							}
							else {
								aList = new ArrayList<Value>(aGraph.getValues(mResource, mProperty));
							}


							//return new ToObjectFunction(mSource, null, (AccessibleObject) mAccessor, null).apply(aList);
							Collection<Object> aValues = BeanReflectUtil.instantiateCollectionFromField(BeanReflectUtil.classFrom(aAccess));

							for (Value aValue : aList) {
								Object aListValue = null;

								try {
									aListValue = getProxyOrDbObject(mAccessor, aClass, aValue, mSource);
								}
								catch (Exception e) {
									// we'll throw an error in a second...
								}

								if (aListValue == null) {
									throw new RuntimeException("Error converting a list value: " + aValue + " -> " + aClass);
								}

								aValues.add(aListValue);

							}
							
							return aValues;
						}
					}
					catch (QueryException e) {
						throw new RuntimeException(e);
					}
				}

				try {
					return getProxyOrDbObject(mAccessor, aClass, aBNode, mSource);
				}
				catch (Exception e) {
					if (EmpireOptions.STRICT_MODE) {
						throw new RuntimeException(e);
					}
					else {
						return null;
					}
				}
			}
			else if (theValue instanceof URI) {
				URI aURI = (URI) theValue;
				try {
					// we need to figure out what type of bean this instance maps to.
					Class<?> aClass = BeanReflectUtil.classFrom(mAccessor);

					aClass = refineClass(mAccessor, aClass, mSource, aURI);

					if (aClass.isAssignableFrom(java.net.URI.class)) {
						return java.net.URI.create(aURI.toString());
					}
					else {
						return getProxyOrDbObject(mAccessor, aClass, java.net.URI.create(aURI.toString()), mSource);
					}
				}
				catch (Exception e) {
					if (EmpireOptions.STRICT_MODE) {
						throw new RuntimeException(e);
					}
					else {
						LOGGER.warn("Problem applying value : " + e.toString() + ", " + e.getCause() );
						return null;
					}
				}
			}
			else {
				if (EmpireOptions.STRICT_MODE) {
					throw new RuntimeException("Unexpected Value type");
				}
				else {
					LOGGER.warn("Problem applying value : Unexpected Value type" );
					return null;
				}
			}
		}
	}

	private static List<Value> asList(DataSource theSource, Resource theRes) throws DataSourceException {
        List<Value> aList = new ArrayList<Value>();

        Resource aListRes = theRes;

        while (aListRes != null) {

            Resource aFirst = (Resource) DataSourceUtil.getValue(theSource, aListRes, RDF.FIRST);
            Resource aRest = (Resource) DataSourceUtil.getValue(theSource, aListRes, RDF.REST);

            if (aFirst != null) {
               aList.add(aFirst);
            }

            if (aRest == null || aRest.equals(RDF.NIL)) {
               aListRes = null;
            }
            else {
                aListRes = aRest;
            }
        }

        return aList;
	}

	private static final MethodFilter METHOD_FILTER = new MethodFilter() {
		public boolean isHandled(final Method theMethod) {
			return !theMethod.getName().equals("finalize");
		}
	};

	@SuppressWarnings("unchecked")
	private static <T> T getProxyOrDbObject(Object theAccessor, Class<T> theClass, Object theKey, DataSource theSource) throws Exception {
		if (BeanReflectUtil.isFetchTypeLazy(theAccessor)) {
			Proxy<T> aProxy = new Proxy<T>(theClass, asPrimaryKey(theKey), theSource);

			ProxyFactory aFactory = new ProxyFactory();
			aFactory.setSuperclass(theClass);
			aFactory.setFilter(METHOD_FILTER);
			
			Object aObj = aFactory.createClass().newInstance();

			((ProxyObject) aObj).setHandler(new ProxyHandler<T>(aProxy));

			return (T) aObj;
		}
		else {
			return fromRdf(theClass, asPrimaryKey(theKey), theSource);
		}
	}

	/**
	 * Javassist {@link MethodHandler} implementation for method proxying.
	 * @param <T> the proxy class type
	 */
	private static class ProxyHandler<T> implements MethodHandler {

		/**
		 * The proxy object which wraps the instance being proxied.
		 */
		private Proxy<T> mProxy;

		/**
		 * Create a new ProxyHandler
		 * @param theProxy the proxy object
		 */
		private ProxyHandler(final Proxy<T> theProxy) {
			mProxy = theProxy;
		}

		/**
		 * Delegates the methods to the Proxy
		 * @inheritDoc
		 */
		public Object invoke(final Object theThis, final Method theMethod, final Method theProxyMethod, final Object[] theArgs) throws Throwable {
			return theMethod.invoke(mProxy.value(), theArgs);
		}
	}
	
	private static String getBNodeConstructQuery(DataSource theSource, Resource theRes, URI theProperty) {
		Dialect aDialect = theSource.getQueryFactory().getDialect();
System.err.println(theRes + " " + theProperty);
if (theRes == null && theProperty == null) {
	int f = 0;
}
		String aSerqlQuery = "construct * from {" + aDialect.asQueryString(theRes) + "} <" + theProperty.toString() + "> {o}, {o} po {oo}";

		String aSparqlQuery = "CONSTRUCT  { " + aDialect.asQueryString(theRes) + " <"+theProperty.toString()+"> ?o . ?o ?po ?oo  } \n" +
							  "WHERE\n" +
							  "{ " + aDialect.asQueryString(theRes) + " <" + theProperty.toString() + "> ?o.\n" +
							  "?o ?po ?oo. }";

		if (theSource.getQueryFactory().getDialect() instanceof SerqlDialect) {
			return aSerqlQuery;
		}
		else {
			// TODO: we're just assuming/hoping at this point that they support sparql.  which
			// will most likely be the case, but possibly not always.
			return aSparqlQuery;
		}
	}

	public static class AsValueFunction implements Function<Object, Value> {
		private AccessibleObject mField;
		private RdfProperty annotation;

		public AsValueFunction() {
		}

		public AsValueFunction(final AccessibleObject theField) {
			mField = theField;
			annotation = mField == null ? null : mField.getAnnotation(RdfProperty.class);
		}

		public Value apply(final Object theIn) {
			if (theIn == null) {
				return null;
			}
            else if (!EmpireOptions.STRONG_TYPING && BeanReflectUtil.isPrimitive(theIn)) {
                return FACTORY.createLiteral(theIn.toString());
            }
			else if (Boolean.class.isInstance(theIn)) {
				return FACTORY.createLiteral(Boolean.class.cast(theIn));
			}
			else if (Integer.class.isInstance(theIn)) {
				return FACTORY.createLiteral(Integer.class.cast(theIn));
			}
			else if (Long.class.isInstance(theIn)) {
				return FACTORY.createLiteral(Long.class.cast(theIn));
			}
			else if (Short.class.isInstance(theIn)) {
				return FACTORY.createLiteral(Short.class.cast(theIn));
			}
			else if (Double.class.isInstance(theIn)) {
				return FACTORY.createLiteral(Double.class.cast(theIn));
			}
			else if (Float.class.isInstance(theIn)) {
				return FACTORY.createLiteral(Float.class.cast(theIn));
			}
			else if (Date.class.isInstance(theIn)) {
				return FACTORY.createLiteral(BasicUtils.datetime(Date.class.cast(theIn)), XMLSchema.DATETIME);
			}
			else if (String.class.isInstance(theIn)) {
				if (annotation != null && !annotation.language().equals("")) {
					return FACTORY.createLiteral(String.class.cast(theIn), annotation.language());
				}
				else {
					return FACTORY.createLiteral(String.class.cast(theIn));
				}
			}
			else if (Character.class.isInstance(theIn)) {
				return FACTORY.createLiteral(Character.class.cast(theIn));
			}
			else if (java.net.URI.class.isInstance(theIn)) {
				if (annotation.isXsdUri()) {
					return FACTORY.createLiteral(theIn.toString(), XMLSchema.ANYURI);
				}
				else {
                	return FACTORY.createURI(theIn.toString());
				}
			}
			else if (Value.class.isAssignableFrom(theIn.getClass())) {
				return Value.class.cast(theIn);
			}
			else if (BeanReflectUtil.hasAnnotation(theIn.getClass(), RdfsClass.class)) {
				try {
					return id(theIn);
				}
				catch (InvalidRdfException e) {
					throw new RuntimeException(e);
				}
			}
			else if (theIn instanceof ProxyHandler) {
				return this.apply( ((ProxyHandler)theIn).mProxy.value());
			}
			else {
				try {
					Field aProxy =  theIn.getClass().getDeclaredField("handler");
					return this.apply(((ProxyHandler)BeanReflectUtil.safeGet(aProxy, theIn)).mProxy.value());
				}
				catch (Exception e) {
					throw new RuntimeException("Unknown type conversion: " + theIn.getClass() + " " + theIn + " " + mField);
				}
			}
		}
	}

	private static class ContainsResourceValues implements Predicate<Value> {
		public boolean accept(final Value theValue) {
			return theValue instanceof Resource;
		}
	}

	private static class LanguageFilter implements Predicate<Value> {
		private String mLangCode;

		private LanguageFilter(final String theLangCode) {
			mLangCode = theLangCode;
		}

		public void setLangCode(final String theLangCode) {
			mLangCode = theLangCode;
		}

		public boolean accept(final Value theValue) {
			return theValue instanceof Literal && mLangCode.equals(((Literal)theValue).getLanguage());
		}
	}
}

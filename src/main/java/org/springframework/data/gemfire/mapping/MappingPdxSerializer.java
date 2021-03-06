/*
 * Copyright 2012-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.gemfire.mapping;

import java.util.Collections;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.convert.EntityInstantiator;
import org.springframework.data.convert.EntityInstantiators;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.mapping.model.ConvertingPropertyAccessor;
import org.springframework.data.mapping.model.MappingException;
import org.springframework.data.mapping.model.PersistentEntityParameterValueProvider;
import org.springframework.data.mapping.model.SpELContext;
import org.springframework.util.Assert;

import com.gemstone.gemfire.pdx.PdxReader;
import com.gemstone.gemfire.pdx.PdxSerializer;
import com.gemstone.gemfire.pdx.PdxWriter;

/**
 * GemFire {@link PdxSerializer} implementation that uses a Spring Data GemFire {@link GemfireMappingContext}
 * to read and write entities.
 * 
 * @author Oliver Gierke
 * @author David Turanski
 * @author John Blum
 * @see org.springframework.context.ApplicationContext
 * @see org.springframework.context.ApplicationContextAware
 * @see org.springframework.core.convert.ConversionService
 * @see org.springframework.data.convert.EntityInstantiator
 * @see org.springframework.data.mapping.PersistentEntity
 * @see org.springframework.data.mapping.PersistentPropertyAccessor
 * @see org.springframework.data.mapping.model.PersistentEntityParameterValueProvider
 * @see org.springframework.data.mapping.model.SpELContext
 * @see com.gemstone.gemfire.pdx.PdxReader
 * @see com.gemstone.gemfire.pdx.PdxSerializer
 * @see com.gemstone.gemfire.pdx.PdxWriter
 */
public class MappingPdxSerializer implements PdxSerializer, ApplicationContextAware {

	private final ConversionService conversionService;

	private EntityInstantiators instantiators;

	private final GemfireMappingContext mappingContext;

	protected final Log log = LogFactory.getLog(getClass());

	private Map<Class<?>, PdxSerializer> customSerializers;

	private SpELContext context;

	/**
	 * Creates a new {@link MappingPdxSerializer} using the default
	 * {@link GemfireMappingContext} and {@link DefaultConversionService}.
	 */
	public MappingPdxSerializer() {
		this(new GemfireMappingContext(), new DefaultConversionService());
	}

	/**
	 * Creates a new {@link MappingPdxSerializer} using the given
	 * {@link GemfireMappingContext} and {@link ConversionService}.
	 *
	 * @param mappingContext must not be {@literal null}.
	 * @param conversionService must not be {@literal null}.
	 */
	public MappingPdxSerializer(GemfireMappingContext mappingContext, ConversionService conversionService) {

		Assert.notNull(mappingContext);
		Assert.notNull(conversionService);

		this.mappingContext = mappingContext;
		this.conversionService = conversionService;
		this.instantiators = new EntityInstantiators();
		this.customSerializers = Collections.emptyMap();
		this.context = new SpELContext(PdxReaderPropertyAccessor.INSTANCE);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(
	 * 	org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.context = new SpELContext(context, applicationContext);
	}

	/* (non-Javadoc) */
	protected ConversionService getConversionService() {
		return conversionService;
	}

	/**
	 * Configures custom PDX serializers to use for specific class types.
	 *
	 * @param customSerializers a mapping of domain object class types and their corresponding PDX serializer.
	 */
	public void setCustomSerializers(Map<Class<?>, PdxSerializer> customSerializers) {
		Assert.notNull(customSerializers);
		this.customSerializers = customSerializers;
	}

	/* (non-Javadoc) */
	protected Map<Class<?>, PdxSerializer> getCustomSerializers() {
		return Collections.unmodifiableMap(customSerializers);
	}

	/**
	 * Configures the {@link EntityInstantiator}s used to create the instances read by this PdxSerializer.
	 *
	 * @param gemfireInstantiators must not be {@literal null}.
	 */
	public void setGemfireInstantiators(Map<Class<?>, EntityInstantiator> gemfireInstantiators) {
		Assert.notNull(gemfireInstantiators);
		this.instantiators = new EntityInstantiators(gemfireInstantiators);
	}

	/* (non-Javadoc) */
	protected EntityInstantiators getGemfireInstantiators() {
		return instantiators;
	}

	/* (non-Javadoc) */
	protected GemfireMappingContext getMappingContext() {
		return mappingContext;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.gemstone.gemfire.pdx.PdxSerializer#fromData(java.lang.Class,
	 * com.gemstone.gemfire.pdx.PdxReader)
	 */
	@Override
	public Object fromData(final Class<?> type, final PdxReader reader) {
		final GemfirePersistentEntity<?> entity = getPersistentEntity(type);

		final Object instance = getInstantiatorFor(entity).createInstance(entity,
			new PersistentEntityParameterValueProvider<GemfirePersistentProperty>(entity,
				new GemfirePropertyValueProvider(reader), null));

		final PersistentPropertyAccessor accessor = new ConvertingPropertyAccessor(entity.getPropertyAccessor(instance),
			getConversionService());

		entity.doWithProperties(new PropertyHandler<GemfirePersistentProperty>() {
			public void doWithPersistentProperty(GemfirePersistentProperty persistentProperty) {
				if (!entity.isConstructorArgument(persistentProperty)) {
					PdxSerializer customSerializer = getCustomSerializer(persistentProperty.getType());

					Object value = null;

					try {
						if (log.isDebugEnabled()) {
							log.debug(String.format("setting property [%1$s] for entity [%2$s] of type [%3$s] from PDX%4$s",
								persistentProperty.getName(), instance, type, (customSerializer != null ?
									String.format(" using custom PdxSerializer [%1$s]", customSerializer) : "")));
						}

						value = (customSerializer != null
							? customSerializer.fromData(persistentProperty.getType(), reader)
							: reader.readField(persistentProperty.getName()));

						if (log.isDebugEnabled()) {
							log.debug(String.format("with value [%1$s]", value));
						}

						accessor.setProperty(persistentProperty, value);
					}
					catch (Exception e) {
						throw new MappingException(String.format(
							"while setting value [%1$s] of property [%2$s] for entity of type [%3$s] from PDX%4$s",
								value, persistentProperty.getName(), type, (customSerializer != null ?
									String.format(" using custom PdxSerializer [%14s]", customSerializer) : "")), e);
					}
				}
			}
		});

		return accessor.getBean();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.gemstone.gemfire.pdx.PdxSerializer#toData(java.lang.Object,
	 * com.gemstone.gemfire.pdx.PdxWriter)
	 */
	@Override
	public boolean toData(final Object value, final PdxWriter writer) {
		GemfirePersistentEntity<?> entity = getPersistentEntity(value.getClass());

		final PersistentPropertyAccessor accessor = new ConvertingPropertyAccessor(entity.getPropertyAccessor(value),
				getConversionService());

		entity.doWithProperties(new PropertyHandler<GemfirePersistentProperty>() {
			@Override
			@SuppressWarnings("unchecked")
			public void doWithPersistentProperty(GemfirePersistentProperty persistentProperty) {
				PdxSerializer customSerializer = getCustomSerializer(persistentProperty.getType());

				Object propertyValue = null;

				try {
					propertyValue = accessor.getProperty(persistentProperty);

					if (log.isDebugEnabled()) {
						log.debug(String.format("serializing value [%1$s] of property [%2$s] for entity of type [%3$s] to PDX%4$s",
							propertyValue, persistentProperty.getName(), value.getClass(), (customSerializer != null ?
								String.format(" using custom PdxSerializer [%1$s]", customSerializer) : "")));
					}

					if (customSerializer != null) {
						customSerializer.toData(propertyValue, writer);
					}
					else {
						writer.writeField(persistentProperty.getName(), propertyValue, (Class) persistentProperty.getType());
					} 
				}
				catch (Exception e) {
					throw new MappingException(String.format(
						"while serializing value [%1$s] of property [%2$s] for entity of type [%3$s] to PDX%4$s",
							propertyValue, persistentProperty.getName(), value.getClass(),
								(customSerializer != null ? String.format(" using custom PdxSerializer [%1$s].",
									customSerializer.getClass().getName()) : ".")), e);
				}
			}
		});

		GemfirePersistentProperty idProperty = entity.getIdProperty();

		if (idProperty != null) {
			writer.markIdentityField(idProperty.getName());
		}

		return true;
	}

	/**
	 * Looks up and returns a custom PdxSerializer based on the class type of the object to (de)serialize.
	 *
	 * @param type the Class type of the object to (de)serialize.
	 * @return a "custom" PdxSerializer for the given class type or null if no custom PdxSerializer
	 * for the given class type was registered.
	 * @see #getCustomSerializers()
	 * @see com.gemstone.gemfire.pdx.PdxSerializer
	 */
	protected PdxSerializer getCustomSerializer(Class<?> type) {
		return getCustomSerializers().get(type);
	}

	/**
	 * Looks up and returns an EntityInstantiator to construct and initialize an instance of the object defined
	 * by the given PersistentEntity (meta-data).
	 *
	 * @param entity the PersistentEntity object used to lookup the custom EntityInstantiator.
	 * @return an EntityInstantiator for the given PersistentEntity.
	 * @see org.springframework.data.convert.EntityInstantiator
	 * @see org.springframework.data.mapping.PersistentEntity
	 */
	protected EntityInstantiator getInstantiatorFor(PersistentEntity entity) {
		return getGemfireInstantiators().getInstantiatorFor(entity);
	}

	/**
	 * Looks up and returns the PersistentEntity meta-data for the given entity class type.
	 *
	 * @param entityType the Class type of the actual persistent entity, application domain object class.
	 * @return the PersistentEntity meta-data for the given entity class type.
	 * @see #getMappingContext()
	 * @see org.springframework.data.gemfire.mapping.GemfirePersistentEntity
	 */
	protected GemfirePersistentEntity<?> getPersistentEntity(Class<?> entityType) {
		return getMappingContext().getPersistentEntity(entityType);
	}

}

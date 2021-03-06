/*
 * Copyright 2002-2013 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.springframework.data.gemfire.function.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Base class for Function Execution bean definition builders.
 *
 * @author David Turanski
 * @author John Blum
 */
abstract class AbstractFunctionExecutionBeanDefinitionBuilder {

	protected final FunctionExecutionConfiguration configuration;

	protected final Log log = LogFactory.getLog(getClass());

	/**
	 * 
	 * @param configuration the configuration values
	 */
	 AbstractFunctionExecutionBeanDefinitionBuilder(FunctionExecutionConfiguration configuration) {
		Assert.notNull(configuration);
		this.configuration = configuration;
	}
	
	/**
	 * Build the bean definition
	 * @param registry
	 * @return
	 */
	 BeanDefinition build(BeanDefinitionRegistry registry) {
		BeanDefinitionBuilder functionProxyFactoryBeanBuilder = BeanDefinitionBuilder.rootBeanDefinition(
			getFunctionProxyFactoryBeanClass());

		functionProxyFactoryBeanBuilder.addConstructorArgValue(configuration.getFunctionExecutionInterface());
		functionProxyFactoryBeanBuilder.addConstructorArgReference(BeanDefinitionReaderUtils.registerWithGeneratedName(
			buildGemfireFunctionOperations(registry), registry));
		 
		return functionProxyFactoryBeanBuilder.getBeanDefinition();
	}	

	protected AbstractBeanDefinition buildGemfireFunctionOperations(BeanDefinitionRegistry registry) {
		BeanDefinitionBuilder functionTemplateBuilder = getGemfireFunctionOperationsBeanDefinitionBuilder(registry);

		functionTemplateBuilder.setLazyInit(true);

		String resultCollectorReference = (String) configuration.getAttribute("resultCollector");

		if (StringUtils.hasText(resultCollectorReference)){
			functionTemplateBuilder.addPropertyReference("resultCollector", resultCollectorReference);
		}

		return functionTemplateBuilder.getBeanDefinition();
	}

	/* Subclasses implement to specify the types to uses. */
	protected abstract Class<?> getFunctionProxyFactoryBeanClass();

	protected abstract BeanDefinitionBuilder getGemfireFunctionOperationsBeanDefinitionBuilder(
		BeanDefinitionRegistry registry);

}

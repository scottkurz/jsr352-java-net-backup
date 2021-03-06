/*
 * Copyright 2012 International Business Machines Corp.
 * 
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. Licensed under the Apache License, 
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.ibm.jbatch.container.impl;

import java.io.Externalizable;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.batch.operations.JobOperator.BatchStatus;
import javax.batch.runtime.StepExecution;

import com.ibm.jbatch.container.AbortedBeforeStartException;
import com.ibm.jbatch.container.IExecutionElementController;
import com.ibm.jbatch.container.artifact.proxy.PartitionAnalyzerProxy;
import com.ibm.jbatch.container.context.impl.StepContextImpl;
import com.ibm.jbatch.container.exception.BatchContainerRuntimeException;
import com.ibm.jbatch.container.jobinstance.RuntimeJobExecutionHelper;
import com.ibm.jbatch.container.jsl.TransitionElement;
import com.ibm.jbatch.container.jsl.ExecutionElement;
import com.ibm.jbatch.container.jsl.Navigator;
import com.ibm.jbatch.container.jsl.NavigatorFactory;
import com.ibm.jbatch.container.jsl.Transition;
import com.ibm.jbatch.container.services.IPersistenceManagerService;
import com.ibm.jbatch.container.servicesmanager.ServicesManagerImpl;
import com.ibm.jbatch.container.status.InternalExecutionElementStatus;
import com.ibm.jbatch.container.util.BatchWorkUnit;
import com.ibm.jbatch.container.util.PartitionDataWrapper;
import com.ibm.jbatch.jsl.model.Decision;
import com.ibm.jbatch.jsl.model.End;
import com.ibm.jbatch.jsl.model.Fail;
import com.ibm.jbatch.jsl.model.Flow;
import com.ibm.jbatch.jsl.model.Split;
import com.ibm.jbatch.jsl.model.Step;
import com.ibm.jbatch.jsl.model.Stop;

public class FlowControllerImpl implements IExecutionElementController {

	private final static String CLASSNAME = PartitionedStepControllerImpl.class.getName();
	private final static Logger logger = Logger.getLogger(CLASSNAME);
	
	private final RuntimeJobExecutionHelper jobExecutionImpl;
	
    private IPersistenceManagerService persistenceService = null;
    
    protected Flow flow;
    
    private final Navigator<Flow> flowNavigator;
	
    //
    // The currently executing controller, this will only be set to the 
    // local variable reference when we are ready to accept stop events for
    // this execution.
    private volatile IExecutionElementController currentStoppableElementController = null;
    
	private PartitionAnalyzerProxy analyzerProxy;

    public FlowControllerImpl(RuntimeJobExecutionHelper jobExecutionImpl, Flow flow) {
        this.jobExecutionImpl = jobExecutionImpl;
        this.flow = flow;
        persistenceService = (IPersistenceManagerService) ServicesManagerImpl.getInstance().getPersistenceManagerService();
        flowNavigator = NavigatorFactory.createFlowNavigator(flow);
    }

   
    @Override
    public InternalExecutionElementStatus execute(List<String> containment, RuntimeJobExecutionHelper rootJobExecution) throws AbortedBeforeStartException {
        final String methodName = "execute";
        if (logger.isLoggable(Level.FINE)) {
            logger.entering(CLASSNAME, methodName);
        }

        try {
            
            // --------------------
            // The same as a simple Job. Loop to complete all steps and decisions in the flow.
            // --------------------
        	return doExecutionLoop(flowNavigator, containment, rootJobExecution);

        } catch (Throwable t) {
                        
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(CLASSNAME + ": caught exception/error: " + t.getMessage() + " : Stack trace: " + sw.toString());
            }
            
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Flow failed with exception/error: " + t.getMessage());
            }

            throw new BatchContainerRuntimeException(t);
        } finally {

            // Persist flow status, setting default if not set

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Flow complete for flow id=" + flow.getId() + ", executionId="
                        + jobExecutionImpl.getExecutionId() /* + ", batchStatus=" + currentFlowContext.getBatchStatus() + ", exitStatus="
                        + currentFlowContext.getExitStatus()*/);
            }

            try {
                //use the job status service here to persist the status        
            } catch (Throwable t) {
                if (logger.isLoggable(Level.WARNING)) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    logger.warning("Caught Throwable on updating execution status: " + sw.toString());
                }
            }

            if (logger.isLoggable(Level.FINE)) {
                logger.exiting(CLASSNAME, methodName);
            }
            
        }
        
    	
    }

    private InternalExecutionElementStatus doExecutionLoop(Navigator<Flow> flowNavigator, List<String> containment, RuntimeJobExecutionHelper rootJobExecution) throws Exception {
        final String methodName = "doExecutionLoop";

        ExecutionElement currentExecutionElement = null;
        try {
            currentExecutionElement = flowNavigator.getFirstExecutionElement(jobExecutionImpl.getRestartOn());
        } catch (Exception e) {
            throw new IllegalArgumentException("Flow doesn't contain a step.", e);
        }

        if (logger.isLoggable(Level.FINE)) {
            logger.fine("First execution element = " + currentExecutionElement.getId());
        }

        // TODO can the first execution element be a decision ??? seems like
        // it's possible

        StepContextImpl<?, ?> stepContext = null;

        ExecutionElement previousExecutionElement = null;
        
        IExecutionElementController previousElementController = null;

        while (true) {

            if (!(currentExecutionElement instanceof Step) && !(currentExecutionElement instanceof Decision) 
            		&& !(currentExecutionElement instanceof Flow) && !(currentExecutionElement instanceof Split)) {
                throw new UnsupportedOperationException("Only support step, and decision within a flow");
            }

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Next execution element = " + currentExecutionElement.getId());
            }

            IExecutionElementController elementController = 
                ExecutionElementControllerFactory.getExecutionElementController(jobExecutionImpl, currentExecutionElement);

            // Depending on the execution element new up the associated context
            // and add it to the controller
            if (currentExecutionElement instanceof Decision) {

                if (previousExecutionElement == null) {
                    // only job context is available to the decider since it is
                    // the first execution element in the job

                    // we need to set to null if batch artifacts are reused
                    elementController.setStepContext(null);

                } else if (previousExecutionElement instanceof Decision) {
                    throw new BatchContainerRuntimeException("A decision cannot precede another decision...OR CAN IT???");
                } else if (previousExecutionElement instanceof Step) {
                    
                    StepExecution lastStepExecution = getLastStepExecution((Step) previousExecutionElement);
                    
                    ((DecisionControllerImpl)elementController).setStepExecution((Step)previousExecutionElement, lastStepExecution);


                } else if (previousExecutionElement instanceof Split) {
                	
                	List<StepExecution> stepExecutions = getSplitStepExecutions(previousElementController);
               		
            		((DecisionControllerImpl)elementController).setStepExecutions((Split)previousExecutionElement, stepExecutions);
            		
                } else if (previousExecutionElement instanceof Flow) {
                	
                    // get last step in flow
                    Step last = getLastStepInTheFlow(previousExecutionElement);
                    
                    // get last step StepExecution
                    StepExecution lastStepExecution = getLastStepExecution(last);
                    
                    ((DecisionControllerImpl)elementController).setStepExecution((Flow)previousExecutionElement, lastStepExecution);
                }

            } else if (currentExecutionElement instanceof Step) {
                String stepId = ((Step) currentExecutionElement).getId();
                stepContext = new StepContextImpl<Object, Externalizable>(stepId);
                elementController.setStepContext(stepContext);
            } else if (currentExecutionElement instanceof Flow) {
            	// do nothing
            } else if (currentExecutionElement instanceof Split) {
            	// do nothing
            }

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Start executing element = " + currentExecutionElement.getId());
            }
            
            /*
             * NOTE:
             * One approach would be to call:  jobStatusService.updateJobCurrentStep()
             * now.  However for something like a flow the element controller (flow controller) will
             * have a better view of what the "current step" is, so let's delegate to it instead. 
             */
            
            this.currentStoppableElementController = elementController;
            InternalExecutionElementStatus executionElementStatus = null;
            try {
                //we need to create a new copy of the containment list to pass around because we
                //don't want to modify the original containment list, since it can get reused
                //multiple times
                ArrayList<String> flowContainment = new ArrayList<String>();
                if (containment != null) {
                    flowContainment.addAll(containment);
                }
                flowContainment.add(flow.getId());
                executionElementStatus = elementController.execute(flowContainment, rootJobExecution);
            } catch (AbortedBeforeStartException e) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Execution failed before even getting to execute execution element = " + currentExecutionElement.getId());
                }
                throw new IllegalStateException("Execution failed before even getting to execute execution element = " + 
                        currentExecutionElement.getId() + "; breaking out of execution loop.");                
            }
            
            // set the execution element controller to null so we don't try to
            // call stop
            // on it after the element has finished executing
            this.currentStoppableElementController = null;
            previousElementController = elementController;

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Done executing element=" + currentExecutionElement.getId() + ", exitStatus=" + executionElementStatus.getExitStatus());
            }

            Transition nextTransition = flowNavigator.getNextTransition(currentExecutionElement, executionElementStatus.getExitStatus());

            if (nextTransition == null) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(methodName + " TODO: is this an expected state or not? ");
                }
                return new InternalExecutionElementStatus(BatchStatus.COMPLETED);
            }
            
            if (nextTransition.getNextExecutionElement() != null) {
                // hold on to the previous execution element for the decider
                // we need it because we need to inject the context of the
                // previous execution element
                // into the decider
                previousExecutionElement = currentExecutionElement;
                currentExecutionElement = nextTransition.getNextExecutionElement();
                      
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(methodName + " , Looping through to next execution element=" + currentExecutionElement.getId());
                }
            } else if (nextTransition.getTransitionElement() != null) {
                // TODO - update job status mgr
                TransitionElement transitionElem = nextTransition.getTransitionElement();

                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(methodName + " , Looping through to next control element=" + transitionElem);
                }

                if (transitionElem instanceof Stop) {
                    String restartOn = ((Stop) transitionElem).getRestart();

                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine(methodName + " , next control element is a <stop> : " + transitionElem + " with restartOn=" + restartOn);
                    }

                    //FIXME jobStatusService.updateJobStatusFromJSLStop(jobInstanceId, restartOn);

                    String newExitStatus = ((Stop) transitionElem).getExitStatus();
                    if (newExitStatus != null && !newExitStatus.isEmpty()) { // overrides with exit status in JSL @exit-status
                        if (logger.isLoggable(Level.FINE)) {
                            logger.fine(methodName + " , on stop, setting new JSL-specified exit status to: " + newExitStatus);
                        }
                    } 
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine(methodName + " Exiting stopped job");
                    }
                    return new InternalExecutionElementStatus(BatchStatus.STOPPED, newExitStatus);

                } else if (transitionElem instanceof End) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine(methodName + " , next control element is an <end>: " + transitionElem);
                    }
                    String newExitStatus = ((End) transitionElem).getExitStatus();
                    if (newExitStatus != null && !newExitStatus.isEmpty()) { // overrides with exit status in JSL @exit-status
                        if (logger.isLoggable(Level.FINE)) {
                            logger.fine(methodName + " , on end, setting new JSL-specified exit status to: " + newExitStatus);
                        }
                    } 
                    return new InternalExecutionElementStatus(BatchStatus.COMPLETED, newExitStatus);
                } else if (transitionElem instanceof Fail) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine(methodName + " , next control element is a <fail>: " + transitionElem);
                    }
                    String newExitStatus = ((Fail) transitionElem).getExitStatus();
                    if (newExitStatus != null && !newExitStatus.isEmpty()) { // overrides with in
                        if (logger.isLoggable(Level.FINE)) {
                            logger.fine(methodName + " , on fail, setting new JSL-specified exit status to: " + newExitStatus);
                        }
                    } // <fail> @exit-status
                    return new InternalExecutionElementStatus(BatchStatus.FAILED, newExitStatus);
                } else {
                    throw new IllegalStateException("Not sure how we'd get here but better than looping.");
                }
            } else {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(methodName + " Exiting as there are no more execution elements= ");
                }
                return new InternalExecutionElementStatus();
            }
        }
		
	}
    
	private List<StepExecution> getSplitStepExecutions(
			IExecutionElementController previousElementController) {
		List<StepExecution> stepExecutions = new ArrayList<StepExecution>();
		if(previousElementController != null) {
			SplitControllerImpl controller = (SplitControllerImpl)previousElementController;
			for (BatchWorkUnit batchWorkUnit : controller.getParallelJobExecs()) {
				                			
				StepExecution lastStepExecution = null;
				List<StepExecution<?>> stepExecs = persistenceService.getStepExecutionIDListQueryByJobID(batchWorkUnit.getJobExecutionImpl().getExecutionId());
				for (StepExecution stepExecution : stepExecs) {
					lastStepExecution = stepExecution;
				}
				stepExecutions.add(lastStepExecution);
			}
		}
		return stepExecutions;
	}

	private StepExecution getLastStepExecution(Step last) {
				
		StepExecution lastStepExecution = null;
		List<StepExecution<?>> stepExecs = persistenceService.getStepExecutionIDListQueryByJobID(jobExecutionImpl.getExecutionId());
		for (StepExecution stepExecution : stepExecs) {
			if(last.getId().equals(stepExecution.getStepName())) {
				lastStepExecution = stepExecution;
			}
		}
		return lastStepExecution;
	}

	private Step getLastStepInTheFlow(ExecutionElement previousExecutionElement) {
		Flow flow = (Flow)previousExecutionElement;
		Step last = null;
		for (ExecutionElement elem : flow.getExecutionElements()) {
			if(elem instanceof Step) {
				last = (Step) elem;
			}
		}
		return last;
	}

	@Override
    public void stop() { 

    }

    public void setStepContext(StepContextImpl<?, ? extends Serializable> stepContext) {
        throw new BatchContainerRuntimeException("Incorrect usage: step context is not in scope within a flow.");
    }

    public void setAnalyzerQueue(PartitionAnalyzerProxy analyzerProxy) {
        this.analyzerProxy = analyzerProxy;
    }


    @Override
    public void setAnalyzerQueue(BlockingQueue<PartitionDataWrapper> analyzerQueue) {
        // no-op
    }

	@Override
	public void setSubJobExitStatusQueue(Stack<String> subJobExitStatusQueue) {
		// no-op
		
	}
}

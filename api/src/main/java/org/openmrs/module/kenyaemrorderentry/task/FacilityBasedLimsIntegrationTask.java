package org.openmrs.module.kenyaemrorderentry.task;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.GlobalProperty;
import org.openmrs.Order;
import org.openmrs.api.context.Context;
import org.openmrs.module.kenyaemrorderentry.ModuleConstants;
import org.openmrs.module.kenyaemrorderentry.api.service.KenyaemrOrdersService;
import org.openmrs.module.kenyaemrorderentry.labDataExchange.LimsSystemWebRequest;
import org.openmrs.module.kenyaemrorderentry.labDataExchange.labsUtils;
import org.openmrs.module.kenyaemrorderentry.queue.LimsQueue;
import org.openmrs.module.kenyaemrorderentry.queue.LimsQueueStatus;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.openmrs.ui.framework.SimpleObject;
import org.openmrs.util.OpenmrsUtil;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * This task sends lab tests to a facility-based lab system.
 */
public class FacilityBasedLimsIntegrationTask extends AbstractTask {
	private Log log = LogFactory.getLog(getClass());
	private static Boolean debugMode = false;

	/**
	 * @see AbstractTask#execute()
	 */
	public void execute() {
		debugMode = labsUtils.isLoggingEnabled();
		if (debugMode) System.out.println("Facility based LIMS-EMR integration: PUSH TASK Starting");
		Context.openSession();
			String limsIntegrationEnabled = "";
			GlobalProperty enableLimsIntegration = Context.getAdministrationService().getGlobalPropertyObject(ModuleConstants.GP_ENABLE_LIMS_INTEGRATION);
			limsIntegrationEnabled = enableLimsIntegration != null ? enableLimsIntegration.getPropertyValue().trim() : null;

			if (limsIntegrationEnabled == null || limsIntegrationEnabled.equals("false")) {
				return;
			}
			KenyaemrOrdersService kenyaemrOrdersService = Context.getService(KenyaemrOrdersService.class);
			Calendar cal = Calendar.getInstance();
			cal.add(Calendar.MINUTE, -2); // upto two minutes ago to ensure billing information (created through AOP) is carefully evaluated
			Date effectiveDate = cal.getTime();
			List<LimsQueue> queuedLabTests = kenyaemrOrdersService.getLimsQueueEntriesByStatus(LimsQueueStatus.QUEUED, null, effectiveDate, false);

			if (queuedLabTests.isEmpty()) {
				if (debugMode) System.out.println("Facility based LIMS-EMR integration PUSH: There are no tests to send to LIMS");
				return;
			}
		int counter = 0;

		for (LimsQueue limsQueue : queuedLabTests) {
			try {
				Order order = limsQueue.getOrder();

				boolean eligible = labsUtils.orderIsInProgress(order) ;
				//	labsUtils.isOrderForExpressPatient(order) ||
				//		!labsUtils.orderIsInProgress(order) ;
				
				if (debugMode) System.out.println("Order eligibility to send to lims  ==>"+eligible);
				
				if (!eligible) {
					continue;
				}
		
				boolean success = LimsSystemWebRequest
					.postLabOrderRequestToLims(limsQueue.getPayload());
				if (debugMode) System.out.println("Order Order sent to lims ==>"+success);
				if (success) {
					limsQueue.setStatus(LimsQueueStatus.SUBMITTED);
					limsQueue.setDateLastChecked(new Date());
					kenyaemrOrdersService.saveLimsQueue(limsQueue);
					counter++;
				}

			} catch (IOException e) {
				if (debugMode) System.out.println(
					"LIMS PUSH IO error for order "
						+ limsQueue.getOrder().getOrderId()
						+ ": " + e.getMessage()
				);

			} catch (Exception e) {
				if (debugMode) System.out.println(
					"LIMS PUSH error for order "
						+ limsQueue.getOrder().getOrderId()
						+ ": " + e.getMessage()
				);
			}
		}

		Context.closeSession();
		if (debugMode) System.out.println("Facility based LIMS-EMR integration PUSH: Number of pushed requests = " + counter);
		}


}

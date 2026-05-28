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

		if (debugMode) {
			System.out.println("Facility based LIMS-EMR integration: PUSH TASK Starting");
		}

		Context.openSession();

		try {

			GlobalProperty enableLimsIntegration =
				Context.getAdministrationService()
					.getGlobalPropertyObject(ModuleConstants.GP_ENABLE_LIMS_INTEGRATION);

			String limsIntegrationEnabled =
				enableLimsIntegration != null
					? enableLimsIntegration.getPropertyValue().trim()
					: null;

			if (limsIntegrationEnabled == null || limsIntegrationEnabled.equals("false")) {
				return;
			}

			KenyaemrOrdersService kenyaemrOrdersService =
				Context.getService(KenyaemrOrdersService.class);

			Calendar cal = Calendar.getInstance();
			cal.add(Calendar.MINUTE, -2);

			Date effectiveDate = cal.getTime();

			int fetched;
			int totalProcessed = 0;

			do {

				List<LimsQueue> queuedLabTests =
					kenyaemrOrdersService.getLimsQueueEntriesByStatus(
						LimsQueueStatus.QUEUED,
						null,
						effectiveDate,
						false
					);

				fetched = queuedLabTests.size();

				if (fetched == 0) {

					if (debugMode) {
						System.out.println("No tests to send to LIMS");
					}

					break;
				}

				for (LimsQueue limsQueue : queuedLabTests) {

					try {

						Order order = limsQueue.getOrder();

						boolean eligible = labsUtils.orderIsInProgress(order);

						if (!eligible) {

							Date dateOrderCreated = limsQueue.getDateCreated();

							if (dateOrderCreated != null) {

								long diffInMillis = new Date().getTime() - dateOrderCreated.getTime();

								long diffInDays =
									java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diffInMillis);

								if (diffInDays > 2) {

									limsQueue.setDateLastChecked(new Date());
									limsQueue.setStatus(LimsQueueStatus.SKIPPED);

									kenyaemrOrdersService.saveLimsQueue(limsQueue);

									if (debugMode) {
										System.out.println(
											"Skipping stale LIMS queue item older than 2 days from date created: " + limsQueue.getUuid());
									}
								}
							}

							continue;
						}

						boolean success =
							LimsSystemWebRequest.postLabOrderRequestToLims(
								limsQueue.getPayload()
							);

						limsQueue.setDateLastChecked(new Date());

						if (success) {
							limsQueue.setStatus(LimsQueueStatus.SUBMITTED);
							totalProcessed++;
						}

						kenyaemrOrdersService.saveLimsQueue(limsQueue);

					} catch (Exception e) {

						log.error("Error processing LIMS queue", e);

						try {
							limsQueue.setDateLastChecked(new Date());
							kenyaemrOrdersService.saveLimsQueue(limsQueue);
						} catch (Exception ex) {
							log.error("Error updating queue item", ex);
						}
					}
				}

			} while (fetched == 10000);

			if (debugMode) {
				System.out.println(
					"Facility based LIMS-EMR integration PUSH: Number of pushed requests = "
						+ totalProcessed
				);
			}

		} finally {
			Context.closeSession();
		}
	}
}

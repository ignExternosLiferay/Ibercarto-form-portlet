/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.webform.portlet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.mail.internet.InternetAddress;
import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.PortletPreferences;
import javax.portlet.PortletRequest;
import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;
import javax.servlet.http.HttpServletRequest;

import com.liferay.counter.service.CounterLocalServiceUtil;
import com.liferay.mail.service.MailServiceUtil;
import com.liferay.portal.kernel.captcha.CaptchaTextException;
import com.liferay.portal.kernel.captcha.CaptchaUtil;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.mail.MailMessage;
import com.liferay.portal.kernel.portlet.PortletResponseUtil;
import com.liferay.portal.kernel.servlet.SessionErrors;
import com.liferay.portal.kernel.servlet.SessionMessages;
import com.liferay.portal.kernel.util.CharPool;
import com.liferay.portal.kernel.util.Constants;
import com.liferay.portal.kernel.util.ContentTypes;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocalizationUtil;
import com.liferay.portal.kernel.util.ParamUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.security.permission.ActionKeys;
import com.liferay.portal.service.permission.PortletPermissionUtil;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portlet.PortletPreferencesFactoryUtil;
import com.liferay.portlet.expando.model.ExpandoRow;
import com.liferay.portlet.expando.service.ExpandoRowLocalServiceUtil;
import com.liferay.portlet.expando.service.ExpandoTableLocalServiceUtil;
import com.liferay.portlet.expando.service.ExpandoValueLocalServiceUtil;
import com.liferay.util.bridges.mvc.MVCPortlet;
import com.liferay.webform.util.PortletPropsValues;
import com.liferay.webform.util.WebFormUtil;

/**
 * @author Daniel Weisser
 * @author Jorge Ferrer
 * @author Alberto Montero
 * @author Julio Camarero
 * @author Brian Wing Shun Chan
 */
public class WebFormPortlet extends MVCPortlet {

	public void deleteData(
			ActionRequest actionRequest, ActionResponse actionResponse)
		throws Exception {

		ThemeDisplay themeDisplay = (ThemeDisplay)actionRequest.getAttribute(
			WebKeys.THEME_DISPLAY);

		String portletId = PortalUtil.getPortletId(actionRequest);

		PortletPermissionUtil.check(
			themeDisplay.getPermissionChecker(), themeDisplay.getPlid(),
			portletId, ActionKeys.CONFIGURATION);

		PortletPreferences preferences =
			PortletPreferencesFactoryUtil.getPortletSetup(actionRequest);

		String databaseTableName = preferences.getValue(
			"databaseTableName", StringPool.BLANK);

		if (Validator.isNotNull(databaseTableName)) {
			ExpandoTableLocalServiceUtil.deleteTable(
				themeDisplay.getCompanyId(), WebFormUtil.class.getName(),
				databaseTableName);
		}
	}

	public void saveData(
			ActionRequest actionRequest, ActionResponse actionResponse)
		throws Exception {

		ThemeDisplay themeDisplay = (ThemeDisplay)actionRequest.getAttribute(
			WebKeys.THEME_DISPLAY);

		String portletId = PortalUtil.getPortletId(actionRequest);

		PortletPreferences preferences =
			PortletPreferencesFactoryUtil.getPortletSetup(
				actionRequest, portletId);
		if(checkCaptchaGoogle(actionRequest)) {
			boolean requireCaptcha = GetterUtil.getBoolean(
				preferences.getValue("requireCaptcha", StringPool.BLANK));
			String successURL = GetterUtil.getString(
				preferences.getValue("successURL", StringPool.BLANK));
			boolean sendAsEmail = GetterUtil.getBoolean(
				preferences.getValue("sendAsEmail", StringPool.BLANK));
			boolean saveToDatabase = GetterUtil.getBoolean(
				preferences.getValue("saveToDatabase", StringPool.BLANK));
			String databaseTableName = GetterUtil.getString(
				preferences.getValue("databaseTableName", StringPool.BLANK));
			boolean saveToFile = GetterUtil.getBoolean(
				preferences.getValue("saveToFile", StringPool.BLANK));
			String fileName = GetterUtil.getString(
				preferences.getValue("fileName", StringPool.BLANK));
			boolean sendEmailToUser = GetterUtil.getBoolean(
					preferences.getValue("sendMailUser", StringPool.BLANK));
	
			if (requireCaptcha) {
				try {
					CaptchaUtil.check(actionRequest);
				}
				catch (CaptchaTextException cte) {
					SessionErrors.add(
						actionRequest, CaptchaTextException.class.getName());
	
					return;
				}
			}
	
			Map<String, String> fieldsMap = new LinkedHashMap<String, String>();
	
			for (int i = 1; true; i++) {
				String fieldLabel = preferences.getValue(
					"fieldLabel" + i, StringPool.BLANK);
	
				String fieldType = preferences.getValue(
					"fieldType" + i, StringPool.BLANK);
	
				if (Validator.isNull(fieldLabel)) {
					break;
				}
	
				if (StringUtil.equalsIgnoreCase(fieldType, "paragraph")) {
					continue;
				}
	
				fieldsMap.put(fieldLabel, actionRequest.getParameter("field" + i));
			}
	
			Set<String> validationErrors = null;
	
			try {
				validationErrors = validate(fieldsMap, preferences);
			}
			catch (Exception e) {
				SessionErrors.add(
					actionRequest, "validationScriptError", e.getMessage().trim());
	
				return;
			}
	
			if (validationErrors.isEmpty()) {
				boolean emailSuccess = true;
				boolean databaseSuccess = true;
				boolean fileSuccess = true;
	
				if (sendAsEmail) {
					emailSuccess = sendEmail(themeDisplay.getCompanyId(), fieldsMap, preferences);
				}
				
				System.out.println("sendEmailToUser "+sendEmailToUser);
				if(sendEmailToUser){
					sendEmailUser(themeDisplay.getCompanyId(), fieldsMap, preferences);
				}
	
				if (saveToDatabase) {
					if (Validator.isNull(databaseTableName)) {
						databaseTableName = WebFormUtil.getNewDatabaseTableName(
							portletId);
	
						preferences.setValue(
							"databaseTableName", databaseTableName);
	
						preferences.store();
					}
	
					databaseSuccess = saveDatabase(
						themeDisplay.getCompanyId(), fieldsMap, preferences,
						databaseTableName);
				}
	
				if (saveToFile) {
					if (!PortletPropsValues.DATA_FILE_PATH_CHANGEABLE) {
						fileName = WebFormUtil.getFileName(themeDisplay, portletId);
					}
	
					fileSuccess = saveFile(fieldsMap, fileName);
				}
	
				if (emailSuccess && databaseSuccess && fileSuccess) {
					if (Validator.isNull(successURL)) {
						SessionMessages.add(actionRequest, "success");
					}
					else {
						SessionMessages.add(
							actionRequest,
							portletId +
								SessionMessages.
									KEY_SUFFIX_HIDE_DEFAULT_SUCCESS_MESSAGE);
					}
				}
				else {
					SessionErrors.add(actionRequest, "error");
				}
			}
			else {
				for (String badField : validationErrors) {
					SessionErrors.add(actionRequest, "error" + badField);
				}
			}
	
			if (SessionErrors.isEmpty(actionRequest) &&
				Validator.isNotNull(successURL)) {
	
				actionResponse.sendRedirect(successURL);
			}
		} else {
			SessionErrors.add(actionRequest, "Error: no se ha checkeado el captcha");
		}
	}


	public void serveResource(
		ResourceRequest resourceRequest, ResourceResponse resourceResponse) {

		String cmd = ParamUtil.getString(resourceRequest, Constants.CMD);

		try {
			if (cmd.equals("captcha")) {
				serveCaptcha(resourceRequest, resourceResponse);
			}
			else if (cmd.equals("export")) {
				exportData(resourceRequest, resourceResponse);
			}
		}
		catch (Exception e) {
			_log.error(e, e);
		}
	}
	
	  // SHB: M�todo comprobador del check de�
	private boolean checkCaptchaGoogle(PortletRequest request) throws Exception {

		/* Obtenemos el HttpServletRequest original */
		HttpServletRequest req = PortalUtil.getOriginalServletRequest(PortalUtil.getHttpServletRequest(request));

		URL url = new URL("https://www.google.com/recaptcha/api/siteverify");
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("secret", "6LcIDDseAAAAAGy9QNayKw2w-KOfm3IMoh3LvK00");
		//params.put("secret", "6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe"); //test
		
		params.put("response", req.getParameter("g-recaptcha-response"));

		StringBuilder postData = new StringBuilder();
		for (Map.Entry<String, Object> param : params.entrySet()) {
			if (postData.length() != 0)
				postData.append('&');
			postData.append(URLEncoder.encode(param.getKey(), "UTF-8"));
			postData.append('=');
			postData.append(URLEncoder.encode(String.valueOf(param.getValue()), "UTF-8"));
		}
		byte[] postDataBytes = postData.toString().getBytes("UTF-8");

		Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("192.168.192.11", 8080));
		HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);

		//HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
		conn.setDoOutput(true);
		conn.getOutputStream().write(postDataBytes);

		Reader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));

		StringBuilder sb = new StringBuilder();
		for (int c; (c = in.read()) >= 0;)
			sb.append((char) c);
		String response = sb.toString();
		System.out.println("Response: " + response);

		JSONObject resp = (JSONObject) com.liferay.portal.kernel.json.JSONFactoryUtil.createJSONObject(response);

		// Si tiene el par�metro 'success' devolvemos el resultado
		if (resp.has("success")) {
			return resp.getBoolean("success");
			// return resp.getBoolean("success");
		} else {
			// Po defecto, false siempre
			return false;
		}
//		return true;
	}

	protected void exportData(
			ResourceRequest resourceRequest, ResourceResponse resourceResponse)
		throws Exception {

		ThemeDisplay themeDisplay = (ThemeDisplay)resourceRequest.getAttribute(
			WebKeys.THEME_DISPLAY);

		String portletId = PortalUtil.getPortletId(resourceRequest);

		PortletPermissionUtil.check(
			themeDisplay.getPermissionChecker(), themeDisplay.getPlid(),
			portletId, ActionKeys.CONFIGURATION);

		PortletPreferences preferences =
			PortletPreferencesFactoryUtil.getPortletSetup(resourceRequest);

		String databaseTableName = preferences.getValue(
			"databaseTableName", StringPool.BLANK);
		String title = preferences.getValue("title", "no-title");

		StringBundler sb = new StringBundler();

		List<String> fieldLabels = new ArrayList<String>();

		for (int i = 1; true; i++) {
			String fieldLabel = preferences.getValue(
				"fieldLabel" + i, StringPool.BLANK);

			String localizedfieldLabel = LocalizationUtil.getPreferencesValue(
				preferences, "fieldLabel" + i, themeDisplay.getLanguageId());

			if (Validator.isNull(fieldLabel)) {
				break;
			}

			fieldLabels.add(fieldLabel);

			sb.append(getCSVFormattedValue(localizedfieldLabel));
			sb.append(PortletPropsValues.CSV_SEPARATOR);
		}

		sb.setIndex(sb.index() - 1);

		sb.append(CharPool.NEW_LINE);

		if (Validator.isNotNull(databaseTableName)) {
			List<ExpandoRow> rows = ExpandoRowLocalServiceUtil.getRows(
				themeDisplay.getCompanyId(), WebFormUtil.class.getName(),
				databaseTableName, QueryUtil.ALL_POS, QueryUtil.ALL_POS);

			for (ExpandoRow row : rows) {
				for (String fieldName : fieldLabels) {
					String data = ExpandoValueLocalServiceUtil.getData(
						themeDisplay.getCompanyId(),
						WebFormUtil.class.getName(), databaseTableName,
						fieldName, row.getClassPK(), StringPool.BLANK);

					sb.append(getCSVFormattedValue(data));
					sb.append(PortletPropsValues.CSV_SEPARATOR);
				}

				sb.setIndex(sb.index() - 1);

				sb.append(CharPool.NEW_LINE);
			}
		}

		String fileName = title + ".csv";
		byte[] bytes = sb.toString().getBytes();
		String contentType = ContentTypes.APPLICATION_TEXT;

		PortletResponseUtil.sendFile(
			resourceRequest, resourceResponse, fileName, bytes, contentType);
	}

	protected String getCSVFormattedValue(String value) {
		StringBundler sb = new StringBundler(3);

		sb.append(CharPool.QUOTE);
		sb.append(
			StringUtil.replace(value, CharPool.QUOTE, StringPool.DOUBLE_QUOTE));
		sb.append(CharPool.QUOTE);

		return sb.toString();
	}

	/**
	 * Monta el cuerpo del mensaje
	 * @param fieldsMap
	 * @return
	 */
	protected String getMailBody(Map<String, String> fieldsMap) {
		StringBundler sb = new StringBundler();

		sb.append("<div style=\"width: 675px;\">");
		sb.append("<img style=\"width: 100%;\" alt=\"X Ibercarto\" src=\"http://www.ibercarto.ign.es/IberCarto-theme/resources/img/home/cabecera_portada_web.jpg\" title=\"X Ibercarto\">");
		sb.append("</div><br/><br/>");
		sb.append("<p style=\"text-align:justify;font-family:Arial,Helvetica,Verdana,sans-serif;font-size:14px;width:675px;\">");
		sb.append("<span style=\"font-size:18px;font-weight:bold;\">Gracias por inscribirse en el d�cimo encuentro de IBERCARTO.</span><br/><br/>");
		sb.append("<span style=\"font-size:14px;\">Os esperamos los d�as 24 y 25 de marzo de 2022 en la sede del <a href=\"https://www.ign.es/\">Instituto Geogr�fico Nacional</a> para celebrar el X Encuentro del Grupo de Trabajo de Cartotecas, con el t�tulo \"Tesoros cartogr�ficos: gesti�n y difusi�n\".</span><br/><br/>");
		sb.append("<span style=\"font-size:14px;\">Acceda a toda su informaci�n a trav�s de la p�gina web oficial de <a href=\"http://www.ibercarto.ign.es//\">IBERCARTO</a>.</span><br/><br/>");
		sb.append("<span style=\"font-size:14px;\">Para cualquier consulta, contacte con ibercarto@mitma.es.</span><br/><br/>");
		sb.append("<strong>---------------------------------------------------------------------------------------------------------------------------------------</strong><br/><strong>Datos de inscripci�n:</strong><br/><br/></p>");
		
		sb.append("<table><tbody>");
		for (String fieldLabel : fieldsMap.keySet()) {
			String fieldValue = fieldsMap.get(fieldLabel);
			sb.append("<tr>");
				sb.append("<td style=\"font-weight: bold;padding-right: 10px;text-align: right;\">");
					sb.append(fieldLabel);
				sb.append("</td>");
				sb.append("<td>");
					sb.append(fieldValue);
				sb.append("</td>");
			sb.append("</tr>");
		}
		sb.append("</tbody></table>");

		sb.append("<p style=\"text-align:justify;font-family:Arial,Helvetica,Verdana,sans-serif;font-size:14px;width:675px;\">");
		sb.append("<strong>---------------------------------------------------------------------------------------------------------------------------------------</strong><br/>");
		sb.append("<span style=\"font-size:10px;color:#6E6E6E;\">");
		sb.append("Advertencia legal:<br/>Este mensaje y, en su caso, los ficheros anexos son confidenciales, incluido lo que respecta a los datos personales, y se dirigen exclusivamente a su destinatario. El IGN/CNIG no autoriza su publicaci�n o difusi�n en papel o digital sin una autorizaci�n expresa. Si usted no es el destinatario y lo ha recibido por error o tiene conocimiento del mismo por cualquier motivo, le rogamos que nos lo comunique por este medio y proceda a destruirlo o borrarlo, y que en todo caso se abstenga de utilizar, reproducir, alterar, archivar o comunicar a terceros el presente mensaje y ficheros anexos. Todo ello bajo pena de incurrir en responsabilidades legales. El emisor no garantiza la integridad, exactitud, rapidez o seguridad del presente correo, ni se responsabiliza de posibles perjuicios derivados de la err�nea interpretaci�n, interceptaci�n de datos, incorporaciones de virus o cualesquiera otras manipulaciones efectuadas por terceros.<br/><br/>");
		sb.append("Disclaimer:<br/>This message and any attached files transmitted with it, is confidential, included as regards personal data. It is intended solely for the use of the individual or entity to whom it is addressed. The IGN/CNIG does not authorize to use the content for its publication or diffusion in paper or digital format without specific authorization. If you are not the intended recipient and have received this information in error or have accessed it for any reason, please notify us of this fact by email reply and then destroy or delete the message, refraining from any reproduction, use, alteration, filing or communication to third parties of this message and attached files on penalty of incurring legal responsibilities.. The sender does not guarantee the integrity, the accuracy, the swift delivery or the security of this email transmission, and assumes no responsibility for any possible damage incurred through erroneous interpretation, data capture, virus incorporation or any manipulation carried out by third parties.<br/><br/>");
		sb.append("</span></p>");

		return sb.toString();
	}

	protected boolean saveDatabase(
			long companyId, Map<String, String> fieldsMap,
			PortletPreferences preferences, String databaseTableName)
		throws Exception {

		WebFormUtil.checkTable(companyId, databaseTableName, preferences);

		long classPK = CounterLocalServiceUtil.increment(
			WebFormUtil.class.getName());

		try {
			for (String fieldLabel : fieldsMap.keySet()) {
				String fieldValue = fieldsMap.get(fieldLabel);

				ExpandoValueLocalServiceUtil.addValue(
					companyId, WebFormUtil.class.getName(), databaseTableName,
					fieldLabel, classPK, fieldValue);
			}

			return true;
		}
		catch (Exception e) {
			_log.error(
				"The web form data could not be saved to the database", e);

			return false;
		}
	}

	protected boolean saveFile(Map<String, String> fieldsMap, String fileName) {
		StringBundler sb = new StringBundler();

		for (String fieldLabel : fieldsMap.keySet()) {
			String fieldValue = fieldsMap.get(fieldLabel);

			sb.append(getCSVFormattedValue(fieldValue));
			sb.append(PortletPropsValues.CSV_SEPARATOR);
		}

		sb.setIndex(sb.index() - 1);

		sb.append(CharPool.NEW_LINE);

		try {
			FileUtil.write(fileName, sb.toString(), false, true);

			return true;
		}
		catch (Exception e) {
			_log.error("The web form data could not be saved to a file", e);

			return false;
		}
	}

	/**
	 * Envia un mail a la persona que hace la reserva
	 * @param companyId
	 * @param fieldsMap
	 * @param preferences
	 * @return
	 */
	protected boolean sendEmailUser(long companyId, Map<String, String> fieldsMap, PortletPreferences preferences) {

			try {
				String emailAddresses = (String)fieldsMap.get("Correo electr�nico (*)");

				if (Validator.isNull(emailAddresses)) {
					_log.error("The web form email cannot be sent because no email address is configured");
					return false;
				}

				InternetAddress fromAddress = new InternetAddress(WebFormUtil.getEmailFromAddress(preferences, companyId), WebFormUtil.getEmailFromName(preferences, companyId));
				String subject = preferences.getValue("subject", StringPool.BLANK);
				String body = getMailBody(fieldsMap);

				MailMessage mailMessage = new MailMessage(fromAddress, subject, body, true);

				InternetAddress[] toAddresses = InternetAddress.parse(emailAddresses);
				mailMessage.setTo(toAddresses);
				MailServiceUtil.sendEmail(mailMessage);
				return true;
			}
			catch (Exception e) {
				_log.error("The web form email could not be sent", e);
				return false;
			}
		}

	protected boolean sendEmail(
		long companyId, Map<String, String> fieldsMap,
		PortletPreferences preferences) {

		try {
			String emailAddresses = preferences.getValue("emailAddress", StringPool.BLANK);

			if (Validator.isNull(emailAddresses)) {
				_log.error("The web form email cannot be sent because no email address is configured");
				return false;
			}

			InternetAddress fromAddress = new InternetAddress(WebFormUtil.getEmailFromAddress(preferences, companyId), WebFormUtil.getEmailFromName(preferences, companyId));
			String subject = preferences.getValue("subject", StringPool.BLANK);
			String body = getMailBody(fieldsMap);

			MailMessage mailMessage = new MailMessage(fromAddress, subject, body, true);

			InternetAddress[] toAddresses = InternetAddress.parse(emailAddresses);

			mailMessage.setTo(toAddresses);
			MailServiceUtil.sendEmail(mailMessage);

			return true;
		}
		catch (Exception e) {
			_log.error("The web form email could not be sent", e);

			return false;
		}
	}

	protected void serveCaptcha(
			ResourceRequest resourceRequest, ResourceResponse resourceResponse)
		throws Exception {

		CaptchaUtil.serveImage(resourceRequest, resourceResponse);
	}

	protected Set<String> validate(
			Map<String, String> fieldsMap, PortletPreferences preferences)
		throws Exception {

		Set<String> validationErrors = new HashSet<String>();

		for (int i = 0; i < fieldsMap.size(); i++) {
			String fieldType = preferences.getValue(
				"fieldType" + (i + 1), StringPool.BLANK);
			String fieldLabel = preferences.getValue(
				"fieldLabel" + (i + 1), StringPool.BLANK);
			String fieldValue = fieldsMap.get(fieldLabel);

			boolean fieldOptional = GetterUtil.getBoolean(
				preferences.getValue(
					"fieldOptional" + (i + 1), StringPool.BLANK));

			if (Validator.equals(fieldType, "paragraph")) {
				continue;
			}

			if (!fieldOptional && Validator.isNotNull(fieldLabel) &&
				Validator.isNull(fieldValue)) {

				validationErrors.add(fieldLabel);

				continue;
			}

			if (!PortletPropsValues.VALIDATION_SCRIPT_ENABLED) {
				continue;
			}

			String validationScript = GetterUtil.getString(
				preferences.getValue(
					"fieldValidationScript" + (i + 1), StringPool.BLANK));

			if (Validator.isNotNull(validationScript) &&
				!WebFormUtil.validate(
					fieldValue, fieldsMap, validationScript)) {

				validationErrors.add(fieldLabel);

				continue;
			}
		}

		return validationErrors;
	}

	private static Log _log = LogFactoryUtil.getLog(WebFormPortlet.class);

}
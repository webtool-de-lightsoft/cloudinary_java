package com.cloudinary;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import com.cloudinary.utils.Base64Coder;
import com.cloudinary.utils.ObjectUtils;
import com.cloudinary.utils.StringUtils;

public class Url {
	private final Configuration config;
	boolean shorten;
	String publicId = null;
	String type = "upload";
	String resourceType = "image";
	String format = null;
	String version = null;
	Transformation transformation = null;
	boolean signUrl;
	String source = null;
	private static final String CL_BLANK = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";


	public Url(Cloudinary cloudinary) {
		this.config = new Configuration(cloudinary.config);
	}

	private static Pattern identifierPattern = Pattern.compile("^(?:([^/]+)/)??(?:([^/]+)/)??(?:v(\\d+)/)?" + "(?:([^#/]+?)(?:\\.([^.#/]+))?)(?:#([^/]+))?$");

	/**
	 * Parses a cloudinary identifier of the form:
	 * [<resource_type>/][<image_type
	 * >/][v<version>/]<public_id>[.<format>][#<signature>]
	 */
	public Url fromIdentifier(String identifier) {
		Matcher matcher = identifierPattern.matcher(identifier);
		if (!matcher.matches()) {
			throw new RuntimeException(String.format("Couldn't parse identifier %s", identifier));
		}

		String resourceType = matcher.group(1);
		if (resourceType != null) {
			resourceType(resourceType);
		}

		String type = matcher.group(2);
		if (type != null) {
			type(type);
		}

		String version = matcher.group(3);
		if (version != null) {
			version(version);
		}

		String publicId = matcher.group(4);
		if (publicId != null) {
			publicId(publicId);
		}

		String format = matcher.group(5);
		if (format != null) {
			format(format);
		}

		// Signature (group 6) is not used

		return this;
	}

	public Url type(String type) {
		this.type = type;
		return this;
	}

	public Url resourcType(String resourceType) {
		return resourceType(resourceType);
	}

	public Url resourceType(String resourceType) {
		this.resourceType = resourceType;
		return this;
	}

	public Url publicId(Object publicId) {
		this.publicId = ObjectUtils.asString(publicId);
		return this;
	}

	public Url format(String format) {
		this.format = format;
		return this;
	}

	public Url cloudName(String cloudName) {
		this.config.cloudName = cloudName;
		return this;
	}

	public Url secureDistribution(String secureDistribution) {
		this.config.secureDistribution = secureDistribution;
		return this;
	}

	public Url cname(String cname) {
		this.config.cname = cname;
		return this;
	}

	public Url version(Object version) {
		this.version = ObjectUtils.asString(version);
		return this;
	}

	public Url transformation(Transformation transformation) {
		this.transformation = transformation;
		return this;
	}

	public Url secure(boolean secure) {
		this.config.secure = secure;
		return this;
	}

	public Url privateCdn(boolean privateCdn) {
		this.config.privateCdn = privateCdn;
		return this;
	}

	public Url cdnSubdomain(boolean cdnSubdomain) {
		this.config.cdnSubdomain = cdnSubdomain;
		return this;
	}

	public Url shorten(boolean shorten) {
		this.config.shorten = shorten;
		return this;
	}

	public Transformation transformation() {
		if (this.transformation == null)
			this.transformation = new Transformation();
		return this.transformation;
	}

	public Url signed(boolean signUrl) {
		this.signUrl = signUrl;
		return this;
	}

	public String generate() {
		return generate(null);
	}

	public String generate(String source) {
		if (type.equals("fetch") && !StringUtils.isEmpty(format)) {
			transformation().fetchFormat(format);
			this.format = null;
		}
		String transformationStr = transformation().generate();
		if (StringUtils.isEmpty(this.config.cloudName)) {
			throw new IllegalArgumentException("Must supply cloud_name in tag or in configuration");
		}

		if (source == null) {
			if (publicId == null) {
				return null;
			}
			source = publicId;
		}
		String original_source = source;

		if (source.toLowerCase(Locale.US).matches("^https?:/.*")) {
			if ("upload".equals(type) || "asset".equals(type)) {
				return original_source;
			}
			source = SmartUrlEncoder.encode(source);
		} else {
			try {
				source = SmartUrlEncoder.encode(URLDecoder.decode(source.replace("+", "%2B"), "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
			if (format != null)
				source = source + "." + format;
		}
		String prefix;
		boolean sharedDomain = !config.privateCdn;
		if (config.secure) {
			if (StringUtils.isEmpty(config.secureDistribution) || Cloudinary.OLD_AKAMAI_SHARED_CDN.equals(config.secureDistribution)) {
				config.secureDistribution = config.privateCdn ? config.cloudName + "-res.cloudinary.com" : Cloudinary.SHARED_CDN;
			}
			sharedDomain = sharedDomain || Cloudinary.SHARED_CDN.equals(config.secureDistribution);
			prefix = "https://" + config.secureDistribution;
		} else {
			CRC32 crc32 = new CRC32();
			crc32.update(source.getBytes());
			String subdomain = config.cdnSubdomain ? "a" + ((crc32.getValue() % 5 + 5) % 5 + 1) + "." : "";
			String host = config.cname != null ? config.cname : (config.privateCdn ? config.cloudName + "-" : "") + "res.cloudinary.com";
			prefix = "http://" + subdomain + host;
		}
		if (sharedDomain)
			prefix = prefix + "/" + config.cloudName;

		if (config.shorten && resourceType.equals("image") && type.equals("upload")) {
			resourceType = "iu";
			type = "";
		}

		if (source.contains("/") && !source.matches("v[0-9]+.*") && !source.matches("https?:/.*") && StringUtils.isEmpty(version)) {
			version = "1";
		}

		if (version == null)
			version = "";
		else
			version = "v" + version;

		String rest = StringUtils.join(new String[] { transformationStr, version, source }, "/");
		rest = rest.replaceAll("^/+", "").replaceAll("([^:])\\/+", "$1/");

		if (signUrl) {
			MessageDigest md = null;
			try {
				md = MessageDigest.getInstance("SHA-1");
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException("Unexpected exception", e);
			}
			byte[] digest = md.digest((rest + this.config.apiSecret).getBytes());
			String signature = Base64Coder.encodeURLSafeString(digest);
			rest = "s--" + signature.substring(0, 8) + "--/" + rest;
		}

		return StringUtils.join(new String[] { prefix, resourceType, type, rest }, "/").replaceAll("([^:])\\/+", "$1/");
	}

	@SuppressWarnings("unchecked")
	public String imageTag(String source) {
		return imageTag(source, ObjectUtils.emptyMap());
	}

	public String imageTag(Map<String, String> attributes) {
		return imageTag("", attributes);
	}

	public String imageTag(String source, Map<String, String> attributes) {
		String url = generate(source);
		attributes = new TreeMap<String, String>(attributes); // Make sure they
																// are ordered.
		if (transformation().getHtmlHeight() != null)
			attributes.put("height", transformation().getHtmlHeight());
		if (transformation().getHtmlWidth() != null)
			attributes.put("width", transformation().getHtmlWidth());

		boolean hiDPI = transformation().isHiDPI();
		boolean responsive = transformation().isResponsive();

		if (hiDPI || responsive) {
			attributes.put("data-src", url);
			String extraClass = responsive ? "cld-responsive" : "cld-hidpi";
			attributes.put("class", (StringUtils.isBlank(attributes.get("class")) ? "" : attributes.get("class") + " ") + extraClass);
			String responsivePlaceholder = attributes.remove("responsive_placeholder");
			if ("blank".equals(responsivePlaceholder)) {
				responsivePlaceholder = CL_BLANK;
			}
			url = responsivePlaceholder;
		}

		StringBuilder builder = new StringBuilder();
		builder.append("<img");
		if (url != null) {
			builder.append(" src='").append(url).append("'");
		}
		for (Map.Entry<String, String> attr : attributes.entrySet()) {
			builder.append(" ").append(attr.getKey()).append("='").append(attr.getValue()).append("'");
		}
		builder.append("/>");
		return builder.toString();
	}

//	public String imageTag(String source, Map<String, String> attributes) {
//		String url = generate(source);
//		attributes = new TreeMap<String, String>(attributes); // Make sure they
//																// are ordered.
//		if (transformation().getHtmlHeight() != null)
//			attributes.put("height", transformation().getHtmlHeight());
//		if (transformation().getHtmlWidth() != null)
//			attributes.put("width", transformation().getHtmlWidth());
//		StringBuilder builder = new StringBuilder();
//		builder.append("<img src='").append(url).append("'");
//		for (Map.Entry<String, String> attr : attributes.entrySet()) {
//			builder.append(" ").append(attr.getKey()).append("='").append(attr.getValue()).append("'");
//		}
//		builder.append("/>");
//		return builder.toString();
//	}

	public String generateSpriteCss(String source) {
		this.type = "sprite";
		if (!source.endsWith(".css"))
			this.format = "css";
		return generate(source);
	}

	public Url source(String source) {
		this.source = source;
		return this;
	}

	public Url source(StoredFile source) {
		if (source.getResourceType() != null)
			this.resourceType = source.getResourceType();
		if (source.getType() != null)
			this.type = source.getType();
		if (source.getVersion() != null)
			this.version = source.getVersion().toString();
		this.format = source.getFormat();
		this.source = source.getPublicId();
		return this;
	}
}

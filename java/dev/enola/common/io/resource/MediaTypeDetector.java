/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2023-2024 The Enola <https://enola.dev> Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.enola.common.io.resource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteSource;
import com.google.common.net.MediaType;

import dev.enola.common.io.iri.URIs;
import dev.enola.common.io.mediatype.MediaTypeProviders;
import dev.enola.common.io.mediatype.MediaTypes;
import dev.enola.common.io.mediatype.YamlMediaType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.FileNameMap;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility for detecting a (better) {@link MediaType} for a Resource.
 *
 * <p>This class is intentionally package private, and should stay so; this is NOT for {@link
 * Resource} API users (who would just use {@link AbstractResource#mediaType()}); it's only used by
 * *Resource SPI implementations. (Which technically makes it impossible to {easily} write *Resource
 * implementations outside of this package; but with the current mono-repo architecture, that's just
 * fine.)
 */
class MediaTypeDetector {
    // NB: This class should *NEVER* implement MediaTypeProvider - that's a separate concern!

    // Default to "application/octet-stream", as per e.g.
    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types/Common_types
    private static final MediaType DEFAULT = com.google.common.net.MediaType.OCTET_STREAM;

    // Ignores certain known to be wrong (bad, invalid) content types
    private static final Set<MediaType> IGNORE =
            ImmutableSet.of(
                    // java.net.URLConnection returns this when there is no content-type header
                    MediaType.parse("content/unknown"));

    private static final Set<MediaType> TRY_FIXING =
            ImmutableSet.of(
                    // raw.githubusercontent.com returns "text/plain" e.g. for *.yaml
                    MediaType.parse("text/plain"));

    private static final Set<String> fileSystemProviderSchemes =
            FileSystemProvider.installedProviders().stream()
                    .map(p -> p.getScheme().toLowerCase())
                    .filter(scheme -> !"jar".equals(scheme))
                    .collect(Collectors.toSet());

    private static final FileNameMap contentTypeMap = URLConnection.getFileNameMap();

    // TODO Remove this, we should (only) use MediaTypeProvider.detect()!
    private final Multimap<String, MediaType> extensionMap =
            MediaTypeProviders.SINGLETON.extensionsToTypes();

    private final FromURI fromExtensionMap =
            uri -> {
                var ext = com.google.common.io.Files.getFileExtension(URIs.getFilename(uri));
                var mediaTypes = extensionMap.get(ext);
                if (mediaTypes.isEmpty()) return Optional.empty();
                var iterator = mediaTypes.iterator();
                var mediaType = iterator.next();
                // TODO Rethink this... this hack is bad (and breaks RosettaTest)
                // We actually really shouldn't need this at all!
                // if (iterator.hasNext())
                //    throw new IllegalStateException(
                //            ext + " has more than 1 MediaType: " + mediaTypes);
                return Optional.of(mediaType);
            };
    private final FromURI fileNameMap =
            uri -> {
                var contentTypeFromFileName =
                        contentTypeMap.getContentTypeFor(URIs.getFilename(uri));
                if (contentTypeFromFileName != null) {
                    return Optional.of(MediaType.parse(contentTypeFromFileName));
                }
                return Optional.empty();
            };
    private final FromURI probeFileContentType =
            uri -> {
                // This doesn't support
                if (uri.getScheme() != null
                        && ("file".equalsIgnoreCase(uri.getScheme())
                                || fileSystemProviderSchemes.contains(
                                        uri.getScheme().toLowerCase()))) {
                    var path = Path.of(URIs.dropQueryAndFragment(uri));
                    try {
                        var contentType = Files.probeContentType(path);
                        if (contentType != null) {
                            return Optional.of(MediaType.parse(contentType));
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return Optional.empty();
            };
    // This is not extensible (e.g. with java.util.ServiceLoader) - because MediaTypes already is
    // TODO fileNameMap & probeFileContentType to JdkMediaTypeProvider implements MediaTypeProvider?
    private final List<FromURI> providers =
            ImmutableList.of(fileNameMap, probeFileContentType, fromExtensionMap);

    /**
     * This is called by Resource* implementation constructors, typically via {@link BaseResource}.
     */
    public MediaType detect(URI uri, ByteSource byteSource) {
        var mediaTypeCharset = URIs.getMediaTypeAndCharset(uri);
        var detected = detect(mediaTypeCharset.mediaType(), mediaTypeCharset.charset(), uri);
        detected = detectCharset(uri, byteSource, detected);
        return (detected != null) ? detected : DEFAULT;
    }

    /**
     * This is called by Resource* implementation constructors, typically via {@link BaseResource}.
     */
    public MediaType overwrite(URI uri, final MediaType originalMediaType) {
        var mediaType = originalMediaType;

        var uriCharset = URIs.getMediaTypeAndCharset(uri);
        var uriMediaType = uriCharset.mediaType();

        if (uriMediaType != null) mediaType = MediaType.parse(uriMediaType);

        var cs = uriCharset.charset();
        if (cs != null) mediaType = mediaType.withCharset(Charset.forName(cs));

        if (!mediaType.charset().isPresent() && originalMediaType.charset().isPresent()) {
            mediaType = mediaType.withCharset(originalMediaType.charset().get());
        } else {
            mediaType = detectCharset(uri, ByteSource.empty(), mediaType);
        }

        return mediaType;
    }

    private MediaType detectCharset(URI uri, ByteSource byteSource, MediaType detected) {
        if (detected != null && !detected.charset().isPresent()) {
            // TODO Make YAML just 1 of many Charset detectors...
            YamlMediaType rcd = new YamlMediaType();
            if (URIs.getFilename(uri).endsWith(".yaml")
                    || MediaTypes.normalizedNoParamsEquals(
                            detected, rcd.knownTypesWithAlternatives().keySet())) {
                var detectedCharset = rcd.detectCharset(uri, byteSource);
                if (detectedCharset.isPresent()) {
                    detected = detected.withCharset(detectedCharset.get());
                }
            }
        }
        return detected;
    }

    // This is not @Deprecated and used e.g. by UrlResource
    public MediaType detect(String contentType, String contentEncoding, URI uri) {
        MediaType mediaType = null;
        if (contentType != null) {
            mediaType = MediaTypes.parse(contentType);
            if (TRY_FIXING.contains(mediaType.withoutParameters())
                    || IGNORE.contains(mediaType.withoutParameters())) {
                mediaType = null;
            }
        }

        if (mediaType == null && uri != null) {
            for (FromURI provider : providers) {
                mediaType = provider.from(uri).orElse(mediaType);
                if (mediaType != null) break;
            }
        }

        if (mediaType == null) {
            if (contentType == null) mediaType = DEFAULT;
            else {
                mediaType = MediaTypes.parse(contentType);
                if (IGNORE.contains(mediaType.withoutParameters())) {
                    mediaType = DEFAULT;
                }
            }
        }

        if (contentEncoding != null) {
            mediaType = mediaType.withCharset(Charset.forName(contentEncoding));
        } else {
            // TODO Replace this with a more "pluggable" instead of this initial hard-coded design
            if (mediaType.is(MediaType.ANY_TEXT_TYPE)) {
                // TODO Remove this; it's wrong! Generic text cannot just be assumed to be UTF-8!
                mediaType = mediaType.withCharset(StandardCharsets.UTF_8);
                // TODO This should move into a TBD JsonMediaType implements ResourceCharsetDetector
            } else if (mediaType.is(MediaType.JSON_UTF_8.withoutParameters())) {
                // TODO See ResourceCharsetDetector above; implement JSON BOM detection à la §3 from
                // https://www.ietf.org/rfc/rfc4627.txt in a new class JsonResourceCharsetDetector
                mediaType = mediaType.withCharset(StandardCharsets.UTF_8);
            } else if (mediaType.subtype().endsWith("+json")) {
                mediaType = mediaType.withCharset(StandardCharsets.UTF_8);
            }
            // TODO NOT if (mediaType.subtype().endsWith("+yaml")) { but via YamlMediaType
        }

        return mediaType;
    }

    private @FunctionalInterface interface FromURI {
        /**
         * Determines MediaType e.g., from URI filename extension or via file system implementation.
         */
        Optional<MediaType> from(URI uri);
    }
}

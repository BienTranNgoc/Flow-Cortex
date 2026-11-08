# Smokescreen forward proxy. The project publishes no image for v0.1.0, only
# release tarballs, so the image is built here from the checksummed tarball.
FROM alpine:3.22@sha256:bfe7ceb9f09ec0566c48fb93356e45a47a00be5a1dd14fa5b909e7709c66b034 AS fetch

ARG TARGETARCH
ARG SMOKESCREEN_VERSION=v0.1.0
ARG SHA256_AMD64=4e1e447449e3cc653dae956208588722b5c8fe8ce894f633adc756a851d1ec46
ARG SHA256_ARM64=f15ddd0dc2b2cbc6d7318f441928939eb9bedfab2a1881508af3096a1e24b7c9

WORKDIR /dl
RUN apk add --no-cache curl
RUN set -eu; \
    case "${TARGETARCH}" in \
      amd64) sum="${SHA256_AMD64}" ;; \
      arm64) sum="${SHA256_ARM64}" ;; \
      *) echo "unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    file="smokescreen_${SMOKESCREEN_VERSION}_linux_${TARGETARCH}.tar.gz"; \
    curl -fsSL --proto '=https' --proto-redir '=https' -o "${file}" \
      "https://github.com/stripe/smokescreen/releases/download/${SMOKESCREEN_VERSION}/${file}"; \
    echo "${sum}  ${file}" | sha256sum -c -; \
    tar -xzf "${file}"; \
    mv "smokescreen_${SMOKESCREEN_VERSION}_linux_${TARGETARCH}/smokescreen" /smokescreen

FROM gcr.io/distroless/static:nonroot@sha256:e2e927ec666bae08560abb3c55d0659eceabb657f56b6782ab500a9fc7f555e3
COPY --from=fetch /smokescreen /smokescreen
EXPOSE 4750
ENTRYPOINT ["/smokescreen"]

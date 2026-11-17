ARG BASE_IMAGE
FROM ${BASE_IMAGE}

ARG BASE_IMAGE
ARG BUILDER_REPO
ARG BUILDER_COMMIT

COPY ps_builder.sh /tmp/ps-build/ps_builder.sh

RUN mkdir -p /tmp/ps-build/work \
    && bash -x /tmp/ps-build/ps_builder.sh --builddir=/tmp/ps-build/work --install_deps=1 \
    && rm -rf /tmp/ps-build /tmp/mysqlclient-req.txt \
    && if command -v apt-get >/dev/null 2>&1; then apt-get clean && rm -rf /var/lib/apt/lists/*; else yum clean all; fi \
    && mkdir -p /usr/share/ps-build \
    && if command -v dpkg-query >/dev/null 2>&1; then \
           dpkg-query -W -f='${Package}:${Architecture}=${Version}\n'; \
       else \
           rpm -qa --qf '%{NAME}-%{VERSION}-%{RELEASE}.%{ARCH}\n'; \
       fi | sort > /usr/share/ps-build/installed-packages.txt

LABEL org.opencontainers.image.source="https://github.com/Percona-Lab/jenkins-pipelines" \
      org.opencontainers.image.base.name="${BASE_IMAGE}" \
      com.percona.ps-build.builder-repo="${BUILDER_REPO}" \
      com.percona.ps-build.builder-commit="${BUILDER_COMMIT}"

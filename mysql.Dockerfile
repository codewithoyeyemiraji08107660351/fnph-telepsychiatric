# MySQL image for Render's private-service runtime.
#
# Compose could bind-mount ./docker/mysql/fnph.cnf straight into the
# container. Render private services only run what's baked into an image,
# so the same config file is copied in at build time instead.

FROM mysql:8.4

COPY fnph.cnf /etc/mysql/conf.d/fnph.cnf

CMD ["mysqld", "--defaults-extra-file=/etc/mysql/conf.d/fnph.cnf"]

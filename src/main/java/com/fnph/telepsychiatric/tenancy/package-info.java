/**
 * The centre tenant filter is defined once, here.
 *
 * A FilterDef is global to the persistence unit, so declaring it on each
 * tenant-owned entity is a duplicate definition and Hibernate refuses to
 * start. It is not a property of any one entity, so it lives on the package
 * rather than on whichever entity happened to be written first.
 *
 * Entities carry the Filter annotation only.
 */
@FilterDef(name = TenantFilters.CENTRE_TENANT,
        parameters = @ParamDef(name = TenantFilters.CENTRE_ID_PARAM, type = Long.class))
package com.fnph.telepsychiatric.tenancy;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
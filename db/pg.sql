
CREATE TABLE public.base_api_info (
	api_id serial4 NOT NULL,
	api_name varchar(512) NULL,
	api_path varchar(512) NULL,
	api_type varchar(30) NULL,
	api_method varchar(30) NULL,
	auth_type varchar(50) NULL,
	enabled int4 NULL,
	api_status varchar(50) NULL,
	group_id int4 NULL,
	parent_id int8 NULL,
	tenant_id varchar(128) NULL,
	page_setup int4 NULL,
	sql_type varchar(20) NULL,
	result_type varchar(30) NULL,
	sql_script text NULL,
	show_sql_script int4 NULL,
	datasource_id int4 NULL,
	datasource_type varchar(50) NULL,
	schema_name varchar(100) NULL,
	release_time timestamp NULL,
	remarks varchar(1000) NULL,
	"version" varchar(100) NULL,
	create_by int4 NULL,
	create_time date NULL,
	update_by int4 NULL,
	update_time date NULL,
	CONSTRAINT base_api_info_pkey PRIMARY KEY (api_id)
);
CREATE INDEX api_path_method_inx ON public.base_api_info USING btree (api_path, api_method);


CREATE TABLE public.base_api_log (
	log_id bigserial NOT NULL,
	api_id int8 NULL,
	api_name varchar(255) NULL,
	auth_type varchar(50) NULL,
	app_name varchar(50) NULL,
	api_method varchar(20) NULL,
	api_path varchar(1000) NULL,
	request_ip varchar(128) NULL,
	query_param varchar(2000) NULL,
	request_body text NULL,
	response_body text NULL,
	response_code int4 NULL,
	body_size int4 NULL,
	request_status varchar(50) NULL,
	request_time timestamp NULL,
	response_time timestamp NULL,
	cost_time int4 DEFAULT 0 NULL,
	visitor_name varchar(50) NULL,
	CONSTRAINT base_api_log_pkey PRIMARY KEY (log_id)
);



CREATE TABLE public.base_api_param (
	param_id serial4 NOT NULL,
	api_id int4 NOT NULL,
	param_name varchar(256) NULL,
	param_type varchar(30) DEFAULT '1'::character varying NULL,
	column_name varchar(256) NULL,
	param_model varchar(30) NULL,
	required varchar(10) DEFAULT 'Y' NULL,
	operation varchar(50) NULL,
	default_value varchar(512) NULL,
	example varchar(512) NULL,
	datasource_id int4 NULL,
	schema_name varchar(128) NULL,
	table_name varchar(256) NULL,
	param_desc varchar(512) NULL,
	create_time date NULL,
	CONSTRAINT base_api_param_pkey PRIMARY KEY (param_id)
);


-- public.base_app definition

-- Drop table

-- DROP TABLE public.base_app;

CREATE TABLE public.base_app (
	app_id serial4 NOT NULL,
	app_name varchar(100) NULL,
	app_desc varchar(300) NULL,
	app_code varchar(128) NULL,
	app_key varchar(128) NULL,
	app_secret varchar(256) NULL,
	strategy_type varchar(20) NULL,
	ips varchar(1000) NULL,
	enabled int4 NULL,
	create_by int4 NULL,
	create_time date NULL,
	update_by int4 NULL,
	update_time date NULL,
	CONSTRAINT base_app_pkey PRIMARY KEY (app_id)
);


-- public.base_app_api definition

-- Drop table

-- DROP TABLE public.base_app_api;

CREATE TABLE public.base_app_api (
	id serial4 NOT NULL,
	app_id int4 NULL,
	api_id int4 NULL,
	create_by int4 NULL,
	create_time date NULL,
	CONSTRAINT base_app_api_pkey PRIMARY KEY (id)
);


-- public.base_datasource definition

-- Drop table

-- DROP TABLE public.base_datasource;

CREATE TABLE public.base_datasource (
	datasource_id serial4 NOT NULL,
	datasource_name varchar(256) NULL,
	datasource_type varchar(50) NULL,
	classify varchar(50) NULL,
	jdbc_url varchar(512) NULL,
	host varchar(50) NULL,
	port varchar(10) NULL,
	username varchar(100) NULL,
	"password" varchar(256) NULL,
	remarks varchar(500) NULL,
	extend varchar(1000) NULL,
	secret_key text NULL,
	create_by int4 NULL,
	create_time date NULL,
	update_time date NULL,
	update_by int4 NULL,
	CONSTRAINT base_datasource_pkey PRIMARY KEY (datasource_id)
);


-- public.base_group definition

-- Drop table

-- DROP TABLE public.base_group;

CREATE TABLE public.base_group (
	group_id serial4 NOT NULL,
	parent_id int4 NULL,
	group_name varchar(500) NULL,
	group_desc varchar(1000) NULL,
	create_by int4 NULL,
	create_time date NULL,
	update_by int4 NULL,
	update_time date NULL,
	CONSTRAINT base_group_pkey PRIMARY KEY (group_id)
);


-- public.base_sys_user definition

-- Drop table

-- DROP TABLE public.base_sys_user;

CREATE TABLE public.base_sys_user (
	user_id serial4 NOT NULL,
	user_name varchar(30) NULL,
	nick_name varchar(30) NULL,
	user_type varchar(2) DEFAULT '0'::character varying NULL,
	"password" varchar(100) DEFAULT ''::character varying NULL,
	"role" varchar(128) NULL,
	email varchar(50) DEFAULT ''::character varying NULL,
	phone varchar(13) DEFAULT ''::character varying NULL,
	sex bpchar(1) DEFAULT '0'::bpchar NULL,
	picture varchar(100) DEFAULT ''::character varying NULL,
	status bpchar(1) DEFAULT '1'::bpchar NULL,
	create_by int4 NULL,
	create_time date NULL,
	update_time date NULL,
	update_by int4 NULL,
	remark varchar(500) NULL,
	CONSTRAINT base_sys_user_pkey PRIMARY KEY (user_id)
);